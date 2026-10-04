package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseTodo;
import com.caseflow.entity.CaseTodoFeedback;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseTodoFeedbackMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.mapper.SysUserMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import com.caseflow.vo.CaseTodoVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 案件待办（to do）：管理员维护，办案人逐项完成。
 *
 * <p>核心约束（需求硬性要求）：**标记完成前必须已上传佐证材料**，
 * 由 {@link #done} 在服务端强制校验——前端禁用勾选框只是提示，不能作为防线。
 */
@Service
public class TodoService {

    @Resource
    private CaseTodoMapper todoMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private SysUserMapper userMapper;
    @Resource
    private FileService fileService;
    @Resource
    private CaseSnapshotService snapshotService;
    @Resource
    private CaseTodoFeedbackMapper feedbackMapper;
    @Resource
    private LogService logService;

    /** 单个案件待办条数上限，防止误批量粘贴把详情页撑爆 */
    private static final int MAX_PER_CASE = 50;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<CaseTodoVO> listOf(Long caseId, boolean withEvidence) {
        if (caseId == null) {
            return new ArrayList<>();
        }
        // 只取顶层主任务（parent_id IS NULL）：子任务在详情弹窗里看，
        // 混进主列表会让「共 N 项」的数字失真，也会让序号含义不明。
        // 存量任务 parent_id 全为 NULL，行为与原来完全一致。
        List<CaseTodo> list = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId)
                .isNull(CaseTodo::getParentId)
                .orderByAsc(CaseTodo::getSort).orderByAsc(CaseTodo::getId));
        // 一次性把子任务统计好，避免每个主任务各查一次（N+1）
        java.util.Map<Long, int[]> subStat = subtaskStat(caseId);
        List<CaseTodoVO> vos = new ArrayList<>();
        for (CaseTodo t : list) {
            CaseTodoVO vo = toVO(t, withEvidence);
            int[] st = subStat.get(t.getId());
            vo.setSubtaskTotal(st == null ? 0 : st[0]);
            vo.setSubtaskDone(st == null ? 0 : st[1]);
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 某案件的子任务统计：parentId → [总数, 已完成数]。
     *
     * <p>一次查询全案件子任务后在内存里聚合——主任务可能有几十条，
     * 逐条查会打出几十个 SQL。
     */
    private java.util.Map<Long, int[]> subtaskStat(Long caseId) {
        java.util.Map<Long, int[]> map = new java.util.HashMap<>();
        List<CaseTodo> subs = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId)
                .isNotNull(CaseTodo::getParentId));
        for (CaseTodo s : subs) {
            int[] arr = map.computeIfAbsent(s.getParentId(), k -> new int[2]);
            arr[0]++;
            if ("DONE".equals(s.getStatus())) {
                arr[1]++;
            }
        }
        return map;
    }

    /** 案件详情用：带佐证材料明细 */
    public List<CaseTodoVO> listOf(Long caseId) {
        return listOf(caseId, true);
    }

    /** 完成情况统计，供案件列表显示进度（done / total） */
    public int[] countsOf(Long caseId) {
        List<CaseTodo> list = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId));
        int total = list.size();
        int done = 0;
        for (CaseTodo t : list) {
            if ("DONE".equals(t.getStatus())) {
                done++;
            }
        }
        return new int[]{done, total};
    }

    /**
     * 待办总览（管理员）：跨案件列出待办及其完成状态与佐证材料。
     *
     * @param status PENDING / DONE / null=全部
     */
    /**
     * 待办总览（按案件类型过滤，2026-10 统一类型选择器）。
     *
     * <p>待办表本身没有 case_type 字段，需先按类型筛出案件 id 集合再 in 过滤。
     * 口径与 {@code CaseService.page} 一致：OTHER = 非刑事、非行政（含未立案与空值）。
     *
     * <p>未传 caseType 时按旧行为不过滤（守卫已保证正常路径总会带上类型，
     * 这里只是兜底，别让接口在缺参数时返回空列表）。
     */
    /**
     * 按案件类型取案件 id 集合（供待办总览过滤）。
     * 口径必须与 CaseService.page 一致：OTHER = 非刑事、非行政（含未立案与历史空值）。
     */
    private List<Long> caseIdsOfType(String caseType) {
        LambdaQueryWrapper<CaseInfo> w = new LambdaQueryWrapper<>();
        if ("OTHER".equalsIgnoreCase(caseType)) {
            w.and(x -> x.notIn(CaseInfo::getCaseType, "CRIMINAL", "ADMINISTRATIVE")
                    .or().isNull(CaseInfo::getCaseType));
        } else {
            w.eq(CaseInfo::getCaseType, caseType);
        }
        return caseMapper.selectList(w).stream().map(CaseInfo::getId).collect(Collectors.toList());
    }

    public List<CaseTodoVO> overview(String status, Long caseId, String caseType) {
        LambdaQueryWrapper<CaseTodo> w = new LambdaQueryWrapper<CaseTodo>()
                .eq(StringUtils.hasText(status), CaseTodo::getStatus, status)
                .eq(caseId != null, CaseTodo::getCaseId, caseId);
        if (StringUtils.hasText(caseType)) {
            List<Long> typeIds = caseIdsOfType(caseType);
            if (typeIds.isEmpty()) {
                return new ArrayList<>();   // 该类型下一件案件都没有，直接空
            }
            w.in(CaseTodo::getCaseId, typeIds);
        }
        w.orderByAsc(CaseTodo::getCaseId)
                .orderByAsc(CaseTodo::getSort)
                .orderByAsc(CaseTodo::getId);
        List<CaseTodo> list = todoMapper.selectList(w);

        List<CaseTodoVO> vos = new ArrayList<>();
        java.util.Map<Long, CaseInfo> cache = new java.util.HashMap<>();
        for (CaseTodo t : list) {
            CaseTodoVO vo = toVO(t, true);
            CaseInfo c = cache.get(t.getCaseId());
            if (c == null && !cache.containsKey(t.getCaseId())) {
                c = caseMapper.selectById(t.getCaseId());
                cache.put(t.getCaseId(), c);
            }
            if (c != null) {
                vo.setCaseNo(c.getCaseNo());
                vo.setCaseName(c.getName());
            }
            vos.add(vo);
        }
        return vos;
    }

    /** 总览汇总数字：待办 / 已完成 / 待上传佐证 */
    public java.util.Map<String, Object> overviewSummary() {
        return overviewSummary(null);
    }

    /**
     * 待办总览汇总（按案件类型过滤，2026-10）。
     * 与 {@link #overview} 同口径：卡片数字与下面列表必须一致。
     */
    public java.util.Map<String, Object> overviewSummary(String caseType) {
        List<CaseTodo> all;
        if (StringUtils.hasText(caseType)) {
            List<Long> typeIds = caseIdsOfType(caseType);
            if (typeIds.isEmpty()) {
                all = new ArrayList<>();
            } else {
                all = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                        .in(CaseTodo::getCaseId, typeIds));
            }
        } else {
            all = todoMapper.selectList(null);
        }
        int total = all.size();
        int done = 0;
        int doneNoEvidence = 0;
        for (CaseTodo t : all) {
            boolean isDone = "DONE".equals(t.getStatus());
            if (isDone) {
                done++;
            }
            if (isDone && fileService.countOfTodo(t.getId()) <= 0) {
                doneNoEvidence++;
            }
        }
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("total", total);
        m.put("done", done);
        m.put("pending", total - done);
        m.put("doneNoEvidence", doneNoEvidence);
        return m;
    }

    // ------------------------------------------------------------------
    // 维护（管理员）
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO add(Long caseId, String content) {
        CaseInfo c = requireCase(caseId);
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写待办内容");
        }
        if (content.trim().length() > 500) {
            throw new BizException("待办内容不能超过 500 字");
        }
        requireAdmin("维护案件待办");
        if (countOfCase(caseId) >= MAX_PER_CASE) {
            throw new BizException("单个案件最多 " + MAX_PER_CASE + " 条待办");
        }

        String before = snapshotService.capture(caseId);
        CaseTodo t = new CaseTodo();
        t.setCaseId(caseId);
        t.setContent(content.trim());
        t.setStatus("PENDING");
        t.setSort(nextSort(caseId));
        t.setCreatedBy(AuthContext.userId());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(t.getCreatedAt());
        todoMapper.insert(t);
        touchCase(c);

        logService.log("CASE", "TODO_ADD", "CASE", caseId,
                "新增待办：" + abbrev(t.getContent()), before, snapshotService.capture(caseId));
        return toVO(t, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO update(Long todoId, String content) {
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        requireAdmin("维护案件待办");
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写待办内容");
        }

        String before = snapshotService.capture(t.getCaseId());
        t.setContent(content.trim());
        t.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(t);
        touchCase(c);

        logService.log("CASE", "TODO_UPDATE", "CASE", t.getCaseId(),
                "修改待办：" + abbrev(t.getContent()), before, snapshotService.capture(t.getCaseId()));
        return toVO(t, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long todoId) {
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        requireAdmin("维护案件待办");

        String before = snapshotService.capture(t.getCaseId());
        todoMapper.deleteById(todoId);
        touchCase(c);

        logService.log("CASE", "TODO_DELETE", "CASE", t.getCaseId(),
                "删除待办：" + abbrev(t.getContent()), before, snapshotService.capture(t.getCaseId()));
    }

    /** 按给定 ID 顺序重排（顺序即 sort） */
    @Transactional(rollbackFor = Exception.class)
    public List<CaseTodoVO> reorder(Long caseId, List<Long> ids) {
        CaseInfo c = requireCase(caseId);
        requireAdmin("维护案件待办");
        if (ids == null || ids.isEmpty()) {
            return listOf(caseId);
        }
        String before = snapshotService.capture(caseId);
        int i = 1;
        for (Long id : ids) {
            CaseTodo t = todoMapper.selectById(id);
            // 只认同属本案的待办，防止越权改动别的案件
            if (t == null || !caseId.equals(t.getCaseId())) {
                continue;
            }
            t.setSort(i++);
            t.setUpdatedAt(LocalDateTime.now());
            todoMapper.updateById(t);
        }
        touchCase(c);
        logService.log("CASE", "TODO_REORDER", "CASE", caseId,
                "调整待办顺序", before, snapshotService.capture(caseId));
        return listOf(caseId);
    }

    // ------------------------------------------------------------------
    // 完成 / 撤销完成
    // ------------------------------------------------------------------

    /**
     * 标记完成。
     *
     * <p><b>2026-10-04 规则变更</b>：原来强制「必须先上传佐证材料」，
     * 但用户已明确不需要佐证材料了。改为两条新约束：
     * <ol>
     *   <li><b>主任务至少要有一条反馈说明</b> —— 否则「完成了」没有依据，
     *       后续无从追溯；对<b>子任务</b>不设此限制（它是主任务的细节，
     *       说明由主任务承载）。</li>
     *   <li><b>子任务全完成才能完成主任务</b> —— 有未完成子任务时
     *       主任务不算做完，直接拒并列出还差哪几项。</li>
     * </ol>
     *
     * <p>佐证数据保留可查（历史已上传的不删），只是不再作为完成门槛。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO done(Long todoId, String remark) {
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        checkOperate(c);
        if ("DONE".equals(t.getStatus())) {
            throw new BizException("该待办已完成，无需重复操作");
        }
        // 规则 2：有子任务时，必须全部完成
        List<CaseTodo> subs = subtasksOf(todoId);
        if (!subs.isEmpty()) {
            List<String> undone = new ArrayList<>();
            for (CaseTodo s : subs) {
                if (!"DONE".equals(s.getStatus())) {
                    undone.add(abbrev(s.getContent()));
                }
            }
            if (!undone.isEmpty()) {
                throw new BizException("还有 " + undone.size() + " 个子任务未完成：" +
                        String.join("、", undone) + "。全部完成后才能勾选本任务。");
            }
        }
        // 规则 1：主任务必须有反馈说明（本次填的 remark，或历史已积累的反馈记录）
        boolean hasNote = StringUtils.hasText(remark) && !remark.trim().isEmpty();
        if (!hasNote && feedbackMapper.selectCount(new LambdaQueryWrapper<CaseTodoFeedback>()
                .eq(CaseTodoFeedback::getTodoId, todoId)) <= 0) {
            throw new BizException("请先填写反馈说明，再勾选完成");
        }

        String before = snapshotService.capture(t.getCaseId());
        LocalDateTime now = LocalDateTime.now();
        t.setStatus("DONE");
        t.setDoneAt(now);
        t.setDoneBy(AuthContext.userId());
        if (hasNote) {
            t.setRemark(remark.trim());
        }
        t.setUpdatedAt(now);
        todoMapper.updateById(t);
        // 反馈说明留痕：本次填的写进累积表，历史 remark 作为首条
        if (hasNote) {
            addFeedback(t, remark.trim());
        }
        touchCase(c);

        logService.log("CASE", "TODO_DONE", "CASE", t.getCaseId(),
                "完成待办：" + abbrev(t.getContent()), before, snapshotService.capture(t.getCaseId()));
        return toVO(t, true);
    }

    /** 某任务的子任务（按 sort、id 排序，与主列表口径一致） */
    public List<CaseTodo> subtasksOf(Long todoId) {
        if (todoId == null) {
            return new ArrayList<>();
        }
        return todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getParentId, todoId)
                .orderByAsc(CaseTodo::getSort).orderByAsc(CaseTodo::getId));
    }

    /** 追加一条反馈记录（累积式，见 CaseTodoFeedback 说明） */
    private void addFeedback(CaseTodo t, String content) {
        CaseTodoFeedback f = new CaseTodoFeedback();
        f.setTodoId(t.getId());
        f.setCaseId(t.getCaseId());
        f.setContent(content);
        f.setStatusAt(t.getStatus());
        f.setCreatorId(AuthContext.userId());
        f.setCreatorName(AuthContext.userName());
        f.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(f);
    }

    /** 撤销完成（退回待办）。管理员操作，便于纠错。 */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO reopen(Long todoId) {
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        requireAdmin("撤销待办完成");
        if (!"DONE".equals(t.getStatus())) {
            throw new BizException("该待办尚未完成，无需撤销");
        }

        String before = snapshotService.capture(t.getCaseId());
        t.setStatus("PENDING");
        t.setDoneAt(null);
        t.setDoneBy(null);
        t.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(t);
        touchCase(c);

        // MyBatis-Plus updateById 会跳过 null，done_at / done_by 需要显式清空
        todoMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CaseTodo>()
                .eq(CaseTodo::getId, todoId)
                .set(CaseTodo::getDoneAt, null)
                .set(CaseTodo::getDoneBy, null));

        logService.log("CASE", "TODO_REOPEN", "CASE", t.getCaseId(),
                "撤销待办完成：" + abbrev(t.getContent()), before, snapshotService.capture(t.getCaseId()));
        return toVO(t, true);
    }

    // ------------------------------------------------------------------
    // 子任务与反馈记录（2026-10-04）
    // ------------------------------------------------------------------

    /**
     * 任务详情：一次返回主任务 + 子任务列表 + 全部反馈记录。
     *
     * <p>做成单个接口而不是三个，是为了让浮窗一次渲染完成——
     * 分三个接口会先闪空态再填充，且三次往返。
     */
    public CaseTodoVO detail(Long todoId) {
        CaseTodo t = requireTodo(todoId);
        CaseTodoVO vo = toVO(t, true);
        List<CaseTodo> subs = subtasksOf(todoId);
        List<CaseTodoVO> subVos = new ArrayList<>();
        int done = 0;
        for (CaseTodo s : subs) {
            CaseTodoVO sv = toVO(s, false);
            subVos.add(sv);
            if ("DONE".equals(s.getStatus())) {
                done++;
            }
        }
        vo.setSubtasks(subVos);
        vo.setSubtaskTotal(subs.size());
        vo.setSubtaskDone(done);
        vo.setFeedbacks(feedbacksOf(todoId));
        return vo;
    }

    /** 某任务的全部反馈记录，按时间正序（累积展示） */
    public List<CaseTodoFeedback> feedbacksOf(Long todoId) {
        return feedbackMapper.selectList(new LambdaQueryWrapper<CaseTodoFeedback>()
                .eq(CaseTodoFeedback::getTodoId, todoId)
                .orderByAsc(CaseTodoFeedback::getCreatedAt)
                .orderByAsc(CaseTodoFeedback::getId));
    }

    /**
     * 添加子任务（细节工作）。**普通用户与管理员均可**——
     * 执行细节最清楚的是干活的人，卡在管理员会拖慢进度。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO addSubtask(Long todoId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写子任务内容");
        }
        CaseTodo parent = requireTodo(todoId);
        CaseInfo c = requireCase(parent.getCaseId());
        // 承办人才能操作本案的待办；管理层全权限（checkOperate 内部已含这两层）
        checkOperate(c);
        if (parent.getParentId() != null) {
            throw new BizException("子任务下不能再加子任务，目前支持两级");
        }
        List<CaseTodo> subs = subtasksOf(todoId);
        if (subs.size() >= MAX_PER_CASE) {
            throw new BizException("子任务数量已达上限（" + MAX_PER_CASE + " 个）");
        }

        CaseTodo s = new CaseTodo();
        s.setCaseId(parent.getCaseId());
        s.setParentId(todoId);
        s.setContent(content.trim());
        s.setStatus("PENDING");
        // 子任务排在主任务之后：取该案件当前最大 sort +1
        int sort = 0;
        for (CaseTodo t : todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, parent.getCaseId()))) {
            if (t.getSort() != null && t.getSort() > sort) {
                sort = t.getSort();
            }
        }
        s.setSort(sort + 1);
        s.setCreatedBy(AuthContext.userId());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        todoMapper.insert(s);
        touchCase(c);

        logService.log("CASE", "TODO_SUBTASK_ADD", "CASE", parent.getCaseId(),
                "为待办「" + abbrev(parent.getContent()) + "」添加子任务：" + abbrev(s.getContent()));
        return toVO(s, false);
    }

    /**
     * 提交反馈（累积一条记录，不改任务状态）。
     *
     * <p>与 done() 的分工：这里只留痕，完成与否由 done() 决定。
     * 之所以拆开，是因为「主任务至少要有一条反馈说明才能完成」
     * 要求反馈和完成是两个动作——先反馈再勾选。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO addFeedback(Long todoId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BizException("请填写反馈内容");
        }
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        checkOperate(c);

        addFeedback(t, content.trim());
        t.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(t);
        touchCase(c);

        logService.log("CASE", "TODO_FEEDBACK", "CASE", t.getCaseId(),
                "待办反馈：" + abbrev(t.getContent()) + " → " + abbrev(content));
        return detail(todoId);
    }

    /**
     * 勾选 / 撤销子任务完成。
     *
     * <p>走统一的 done/reopen，规则自动生效：
     * 子任务无「必须有反馈说明」的限制（说明由主任务承载），
     * 且子任务没有下级故不触发「子任务全完成」检查。
     */
    public CaseTodoVO toggleSubtask(Long subId, boolean done) {
        CaseTodo s = requireTodo(subId);
        if (s.getParentId() == null) {
            throw new BizException("这是主任务，请在待办列表里勾选");
        }
        if (done) {
            CaseTodoVO vo = done(subId, "（子任务完成）");
            return vo;
        }
        return reopen(subId);
    }

    // ------------------------------------------------------------------
    // 指派时批量创建（由 CaseService.assign 调用，权限已在那边校验）
    // ------------------------------------------------------------------

    /** 指派案件时一并写入待办项（空/空白项自动忽略） */
    public void addAll(Long caseId, List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        int sort = nextSort(caseId);
        int created = 0;
        for (String raw : contents) {
            if (!StringUtils.hasText(raw) || created >= MAX_PER_CASE) {
                continue;
            }
            CaseTodo t = new CaseTodo();
            t.setCaseId(caseId);
            t.setContent(raw.trim().length() > 500 ? raw.trim().substring(0, 500) : raw.trim());
            t.setStatus("PENDING");
            t.setSort(sort++);
            t.setCreatedBy(AuthContext.userId());
            t.setCreatedAt(LocalDateTime.now());
            t.setUpdatedAt(t.getCreatedAt());
            todoMapper.insert(t);
            created++;
        }
    }

    /**
     * 覆盖式同步待办（指派弹窗一次提交整份清单时用）：
     * 保留已有项（按内容匹配，避免删掉别人已上传的佐证），删除不在新清单里的未完成项。
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncForAssign(Long caseId, List<String> contents) {
        List<CaseTodo> existing = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId).orderByAsc(CaseTodo::getSort).orderByAsc(CaseTodo::getId));
        List<String> want = new ArrayList<>();
        if (contents != null) {
            for (String s : contents) {
                if (StringUtils.hasText(s)) {
                    want.add(s.trim());
                }
            }
        }
        // 已完成且带佐证的项永不自动删除——那是留痕
        for (CaseTodo t : existing) {
            boolean stillWanted = want.contains(t.getContent());
            if (stillWanted) {
                want.remove(t.getContent()); // 认领一条，重复内容也能一一对应
                continue;
            }
            if ("DONE".equals(t.getStatus()) || fileService.countOfTodo(t.getId()) > 0) {
                continue;
            }
            todoMapper.deleteById(t.getId());
        }
        addAll(caseId, want);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 取某条待办所属案件 ID（上传佐证时用于回填 case_id） */
    public Long caseIdOf(Long todoId) {
        return requireTodo(todoId).getCaseId();
    }

    /** 删除案件时清掉它的待办（内部调用，不做权限校验） */
    public void removeAllOfCase(Long caseId) {
        if (caseId != null) {
            todoMapper.delete(new LambdaQueryWrapper<CaseTodo>().eq(CaseTodo::getCaseId, caseId));
        }
    }

    private CaseInfo requireCase(Long caseId) {
        if (caseId == null) {
            throw new BizException("缺少案件 ID");
        }
        CaseInfo c = caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    private CaseTodo requireTodo(Long todoId) {
        if (todoId == null) {
            throw new BizException("缺少待办 ID");
        }
        CaseTodo t = todoMapper.selectById(todoId);
        if (t == null) {
            throw new BizException("待办不存在或已被删除");
        }
        return t;
    }

    /** 维护类操作限管理层 */
    private void requireAdmin(String what) {
        if (!AuthContext.isFullAccess()) {
            throw new BizException(403, "当前角色无权执行：" + what + "。如需开通请联系所长或法制员。");
        }
    }

    /** 勾选完成：全权限 或 本案现职承办人（与侦查计划同一套判定） */
    private void checkOperate(CaseInfo c) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        Long empId = AuthContext.get() == null ? null : AuthContext.get().getEmployeeId();
        if (empId == null) {
            throw new BizException(403, "只有案件承办人或管理层可以勾选待办完成");
        }
        Long cnt = assigneeMapper.selectCount(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getCaseId, c.getId())
                .eq(CaseAssignee::getEmployeeId, empId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        if (cnt == null || cnt == 0) {
            throw new BizException(403, "只有案件承办人或管理层可以勾选待办完成");
        }
    }

    private int countOfCase(Long caseId) {
        Long n = todoMapper.selectCount(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId));
        return n == null ? 0 : n.intValue();
    }

    private int nextSort(Long caseId) {
        List<CaseTodo> list = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId).orderByDesc(CaseTodo::getSort).last("LIMIT 1"));
        if (list.isEmpty() || list.get(0).getSort() == null) {
            return 1;
        }
        return list.get(0).getSort() + 1;
    }

    private void touchCase(CaseInfo c) {
        if (c != null) {
            c.setUpdatedAt(LocalDateTime.now());
            caseMapper.updateById(c);
        }
    }

    private CaseTodoVO toVO(CaseTodo t, boolean withEvidence) {
        CaseTodoVO vo = new CaseTodoVO();
        vo.setId(t.getId());
        vo.setCaseId(t.getCaseId());
        vo.setContent(t.getContent());
        vo.setStatus(t.getStatus());
        vo.setStatusName("DONE".equals(t.getStatus()) ? "已完成" : "待办");
        vo.setSort(t.getSort());
        vo.setDoneAt(t.getDoneAt());
        vo.setRemark(t.getRemark());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setUpdatedAt(t.getUpdatedAt());
        vo.setParentId(t.getParentId());
        vo.setDoneByName(userName(t.getDoneBy()));
        vo.setCreatedByName(userName(t.getCreatedBy()));
        int cnt = fileService.countOfTodo(t.getId());
        vo.setEvidenceCount(cnt);
        vo.setHasEvidence(cnt > 0);
        if (withEvidence) {
            vo.setEvidence(fileService.filesOfTodo(t.getId()));
        }
        return vo;
    }

    private String userName(Long id) {
        if (id == null) {
            return null;
        }
        SysUser u = userMapper.selectById(id);
        return u == null ? null : u.getDisplayName();
    }

    private String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
