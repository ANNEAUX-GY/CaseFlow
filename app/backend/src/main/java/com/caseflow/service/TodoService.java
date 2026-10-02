package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseTodo;
import com.caseflow.entity.SysUser;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
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
        List<CaseTodo> list = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getCaseId, caseId)
                .orderByAsc(CaseTodo::getSort).orderByAsc(CaseTodo::getId));
        List<CaseTodoVO> vos = new ArrayList<>();
        for (CaseTodo t : list) {
            vos.add(toVO(t, withEvidence));
        }
        return vos;
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
    public List<CaseTodoVO> overview(String status, Long caseId) {
        LambdaQueryWrapper<CaseTodo> w = new LambdaQueryWrapper<CaseTodo>()
                .eq(StringUtils.hasText(status), CaseTodo::getStatus, status)
                .eq(caseId != null, CaseTodo::getCaseId, caseId)
                .orderByAsc(CaseTodo::getCaseId)
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
        List<CaseTodo> all = todoMapper.selectList(null);
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
     * 标记完成。**必须已上传佐证材料**，否则拒绝——
     * 这是需求里明确要求的硬约束，前端 disabled 之外服务端还要再挡一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO done(Long todoId, String remark) {
        CaseTodo t = requireTodo(todoId);
        CaseInfo c = requireCase(t.getCaseId());
        checkOperate(c);
        if ("DONE".equals(t.getStatus())) {
            throw new BizException("该待办已完成，无需重复操作");
        }
        if (fileService.countOfTodo(todoId) <= 0) {
            throw new BizException("请先上传佐证材料，再勾选完成");
        }

        String before = snapshotService.capture(t.getCaseId());
        t.setStatus("DONE");
        t.setDoneAt(LocalDateTime.now());
        t.setDoneBy(AuthContext.userId());
        t.setRemark(remark);
        t.setUpdatedAt(t.getDoneAt());
        todoMapper.updateById(t);
        touchCase(c);

        logService.log("CASE", "TODO_DONE", "CASE", t.getCaseId(),
                "完成待办：" + abbrev(t.getContent()), before, snapshotService.capture(t.getCaseId()));
        return toVO(t, true);
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
