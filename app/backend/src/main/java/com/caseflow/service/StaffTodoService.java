package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CaseLeaderOpinion;
import com.caseflow.entity.CaseTodo;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.exception.BizException;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CaseLeaderOpinionMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.support.LogService;
import com.caseflow.vo.CaseTodoVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 民警端待办事项（2026-10-04）。
 *
 * <p><b>数据来源：由领导意见自动派生</b>。领导在意见面板提一条意见，
 * 本类就为该意见派生一条待办（{@code opinion_id} 关联，幂等）。
 * 不做两套人工录入——意见提了忘建待办、或待办建了没对应意见，
 * 这两种"数据对不上"是这个模块最常见的坑。
 *
 * <p><b>紧急程度 vs 截止时间</b>（刻意分开）：
 * <ul>
 *   <li>紧急程度：<b>手动三档</b>（一般/较急/紧急），是人的判断，不随时间变化；</li>
 *   <li>截止时间：客观约束，<b>只影响「即将超期」判定</b>。</li>
 * </ul>
 * 排序只用紧急程度——若用截止时间排，待办会随时间自己往前挪，
 * 用户会发现"什么都没动，列表顺序变了"。
 *
 * <p><b>权限</b>：普通民警只能看到本人相关（承办/协办）的待办，
 * 复用 {@code CaseService.myVisibleCaseIds()} 的收敛口径，
 * 与案件列表、案件盯办保持一致，不另立一套。
 */
@Service
public class StaffTodoService {

    /** 即将超期阈值（天）：距截止不足该天数即计入「即将超期」 */
    public static final int DUE_SOON_DAYS = 3;

    @Resource
    private CaseTodoMapper todoMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseLeaderOpinionMapper opinionMapper;
    @Resource
    private com.caseflow.mapper.SysUserMapper userMapper;
    @Resource
    private CaseService caseService;
    @Resource
    private LogService logService;

    // ==================================================================
    // 一、派生：从领导意见生成待办
    // ==================================================================

    /**
     * 为一条领导意见派生待办（幂等：同一条意见只派生一次）。
     *
     * <p>领导意见的三个字段直接映射到待办：
     * <ul>
     *   <li>意见内容 → 任务标题</li>
     *   <li>意见截止时间 deadline → 待办截止时间</li>
     *   <li>提意见人的部门 → 所属部门/来源</li>
     * </ul>
     * 紧急/重点程度用意见的importance 映射（A最重要→重点+紧急，B重要→次重点+较急，
     * C一般→一般+一般），领导之后可在民警端调整。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodo deriveFromOpinion(CaseLeaderOpinion op) {
        if (op == null || op.getId() == null) {
            return null;
        }
        // 幂等：已有同源待办就不再建
        CaseTodo exist = todoMapper.selectOne(new LambdaQueryWrapper<CaseTodo>()
                .eq(CaseTodo::getOpinionId, op.getId())
                .last("LIMIT 1"));
        if (exist != null) {
            return exist;
        }
        CaseTodo t = new CaseTodo();
        t.setCaseId(op.getCaseId());
        t.setOpinionId(op.getId());
        t.setContent(abbrev(op.getContent(), 500));
        t.setStatus("PENDING");
        t.setDeadline(op.getDeadline());
        t.setDeptSource(deptOfCreator(op.getCreatorId()));
        // 意见的重要性映射为待办的紧急 + 重点两档，领导可再调整
        t.setUrgency(urgencyOfOpinion(op.getImportance()));
        t.setImportance(importanceOfOpinion(op.getImportance()));
        t.setSort(0);
        t.setCreatedBy(op.getCreatorId());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(t.getCreatedAt());
        todoMapper.insert(t);
        return t;
    }

    /**
     * 意见 → 待办紧急程度：A→紧急、B→较急、C/空→一般。
     * 领导意见的A/B/C 本就是"重要性"，映射成"紧急程度"是合理默认，
     * 之后民警端可自行调整。
     */
    private String urgencyOfOpinion(String importance) {
        String v = StringUtils.hasText(importance) ? importance.trim().toUpperCase() : "";
        if ("A".equals(v)) {
            return CaseTodo.URG_URGENT;
        }
        if ("B".equals(v)) {
            return CaseTodo.URG_HIGH;
        }
        return CaseTodo.URG_NORMAL;
    }

    /** 意见 → 待办重点程度：A→重点、B→次重点、C/空→一般 */
    private String importanceOfOpinion(String importance) {
        String v = StringUtils.hasText(importance) ? importance.trim().toUpperCase() : "";
        if ("A".equals(v)) {
            return CaseTodo.IMP_KEY;
        }
        if ("B".equals(v)) {
            return CaseTodo.IMP_MEDIUM;
        }
        return CaseTodo.IMP_NORMAL;
    }

    /**
     * 提意见人所在部门。
     *
     * <p>直接查 sys_user.dept（冗余在账号上的部门字段），查不到回退"管理层"。
     * <b>不因部门缺失阻断派生</b>——领导没录部门是档案问题，
     * 不该让"提意见"这件事本身失败。
     */
    private String deptOfCreator(Long userId) {
        if (userId == null) {
            return "管理层";
        }
        try {
            com.caseflow.entity.SysUser u = userMapper.selectById(userId);
            if (u != null && StringUtils.hasText(u.getDept())) {
                return u.getDept();
            }
        } catch (Exception e) {
            // 部门反查失败不影响主流程
        }
        return "管理层";
    }

    // ==================================================================
    // 二、民警端列表
    // ==================================================================

    /**
     * 本人待办列表。
     *
     * <p><b>排序规则</b>（默认：紧急→ 重点 → 截止时间近的在前）：
     * <ol>
     *   <li>紧急程度降序（URGENT紧急 &gt; HIGH 较急 &gt; NORMAL 一般）</li>
     *   <li>重点程度降序（KEY 重点 &gt; MEDIUM 次重点 &gt; NORMAL 一般）</li>
     *   <li>截止时间升序（早的在前；无期限的沉到最后）</li>
     * </ol>
     *
     * <p>支持多字段组合排序：传 sortBy 多个字段（如 {@code urgency,importance,deadline}），
     * 按传入顺序依次比较——这就是「组合排序」，用户点两列就等于加两个排序条件。
     * urgencyOrder / importanceOrder 各自支持 asc / desc。
     *
     * <p><b>权限</b>：范围强制收敛到 {@code myVisibleCaseIds()}——
     * 别人（甚至管理层）传什么都不扩大范围，普通民警只见本人相关的。
     */
    public List<CaseTodoVO> myTodos(String status, String sortBy, String urgencyOrder,
                                    String importanceOrder, String keyword) {
        java.util.Set<Long> mine = caseService.myVisibleCaseIds();
        if (mine == null || mine.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<CaseTodo> w = new LambdaQueryWrapper<CaseTodo>()
                .in(CaseTodo::getCaseId, mine)
                .ne(CaseTodo::getStatus, "CANCELLED");
        if (StringUtils.hasText(status)) {
            w.eq(CaseTodo::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            w.like(CaseTodo::getContent, keyword.trim());
        }
        List<CaseTodo> list = todoMapper.selectList(w);
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        sortTodos(list, sortBy, urgencyOrder, importanceOrder);
        return toVOs(list);
    }

    /**
     * 多字段组合排序。
     *
     * @param sortBy逗号分隔的排序字段，如 "urgency,importance"；为空走默认
     */
    private void sortTodos(List<CaseTodo> list, String sortBy, String uOrder, String iOrder) {
        String[] fields = StringUtils.hasText(sortBy)
                ? sortBy.split(",")
                : new String[]{"urgency", "importance", "deadline"};
        boolean uDesc = !"asc".equalsIgnoreCase(uOrder);   // 紧急程度默认降序（紧急在前）
        boolean iDesc = !"asc".equalsIgnoreCase(iOrder);   // 重点程度默认降序（重点在前）

        Comparator<CaseTodo> cmp = null;
        for (String f : fields) {
            String key = f.trim().toLowerCase();
            Comparator<CaseTodo> c;
            switch (key) {
                case "urgency":
                    c = Comparator.comparingInt((CaseTodo t) -> urgencyWeight(t.getUrgency()));
                    if (uDesc) {
                        c = c.reversed();
                    }
                    break;
                case "importance":
                    c = Comparator.comparingInt((CaseTodo t) -> importanceWeight(t.getImportance()));
                    if (iDesc) {
                        c = c.reversed();
                    }
                    break;
                case "deadline":
                    // 无期限的沉到最后：null排最后
                    c = Comparator.comparing(CaseTodo::getDeadline,
                            Comparator.nullsLast(Comparator.naturalOrder()));
                    break;
                case "createdat":
                case "created_at":
                    c = Comparator.comparing(CaseTodo::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).reversed();
                    break;
                default:
                    continue;
            }
            cmp = (cmp == null) ? c : cmp.thenComparing(c);
        }
        if (cmp == null) {
            cmp = Comparator.comparingInt((CaseTodo t) -> urgencyWeight(t.getUrgency())).reversed();
        }
        list.sort(cmp);
    }

    // ==================================================================
    // 三、民警端可改字段
    // ==================================================================

    /**
     * 民警端调整待办的紧急/重点程度（只能改自己承办案件的）。
     * 状态流转仍走done/reopen（那里要校验佐证材料），这里只管分级。
     */
    @Transactional(rollbackFor = Exception.class)
    public CaseTodoVO updateGrade(Long todoId, String urgency, String importance) {
        CaseTodo t = todoMapper.selectById(todoId);
        if (t == null) {
            throw new BizException("待办不存在或已被删除");
        }
        requireMine(t.getCaseId());
        if (StringUtils.hasText(urgency)) {
            t.setUrgency(normalizeUrgency(urgency));
        }
        if (StringUtils.hasText(importance)) {
            t.setImportance(normalizeImportance(importance));
        }
        t.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(t);
        return toVO(t, false);
    }

    private String normalizeUrgency(String v) {
        String x = v.trim().toUpperCase();
        if (CaseTodo.URG_URGENT.equals(x) || CaseTodo.URG_HIGH.equals(x) || CaseTodo.URG_NORMAL.equals(x)) {
            return x;
        }
        return CaseTodo.URG_NORMAL;
    }

    private String normalizeImportance(String v) {
        String x = v.trim().toUpperCase();
        if (CaseTodo.IMP_KEY.equals(x) || CaseTodo.IMP_MEDIUM.equals(x) || CaseTodo.IMP_NORMAL.equals(x)) {
            return x;
        }
        return CaseTodo.IMP_NORMAL;
    }

    // ==================================================================
    // 四、登录欢迎弹窗汇总
    // ==================================================================

    /**
     * 登录欢迎弹窗的数据源（全部按当前登录人收敛）。
     *
     * <p>三个数字的口径：
     * <ul>
     *   <li>{@code todayTodoCount}：今日需完成 = 待办未完成 且 截止为今天</li>
     *   <li>{@code dueSoonCount}：即将超期 = 未完成 且 距截止 ≤ 3 天（含已超期）</li>
     *   <li>{@code newOpinionCount}：新增领导审批意见 = 本人承办案件里、反馈状态为空的意见条数</li>
     * </ul>
     * 点条目跳转时用{@code linkType/linkQuery} 让前端直接带上筛选条件跳过去。
     */
    public java.util.Map<String, Object> welcomeSummary() {
        java.util.Map<String, Object> m = new HashMap<>();
        java.util.Set<Long> mine = caseService.myVisibleCaseIds();
        if (mine == null || mine.isEmpty()) {
            m.put("todayTodoCount", 0);
            m.put("dueSoonCount", 0);
            m.put("newOpinionCount", 0);
            m.put("overdueCount", 0);
            return m;
        }
        List<CaseTodo> todos = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .in(CaseTodo::getCaseId, mine)
                .eq(CaseTodo::getStatus, "PENDING")   // 只算未完成的
                .ne(CaseTodo::getStatus, "CANCELLED"));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayEnd = now.toLocalDate().atTime(23, 59, 59);
        int today = 0, dueSoon = 0, overdue = 0;
        for (CaseTodo t : todos) {
            LocalDateTime dl = t.getDeadline();
            if (dl == null) {
                continue;
            }
            if (!dl.isAfter(todayEnd)) {
                today++;
            }
            if (dl.isBefore(now)) {
                overdue++;
            }
            if (!dl.isAfter(now.plusDays(DUE_SOON_DAYS))) {
                dueSoon++;
            }
        }
        // 领导意见：本人承办案件里尚未反馈的条数
        Long newOpinion = opinionMapper.selectCount(new LambdaQueryWrapper<CaseLeaderOpinion>()
                .in(CaseLeaderOpinion::getCaseId, mine)
                .isNull(CaseLeaderOpinion::getFeedbackStatus));

        m.put("todayTodoCount", today);
        m.put("dueSoonCount", dueSoon);
        m.put("overdueCount", overdue);
        m.put("newOpinionCount", newOpinion == null ? 0 : newOpinion.intValue());
        return m;
    }

    // ==================================================================
    // 五、历史意见补派生
    // ==================================================================

    /**
     * 为「尚无对应待办」的历史领导意见补派生待办（幂等）。
     *
     * <p>用途：本模块上线前案件库里已有领导意见（当时没有待办功能），
     * 需要一次性补齐，否则民警端看不到这些历史要求。
     * 走 {@code deriveFromOpinion} 的幂等逻辑，重复调用不会重复建。
     *
     * @return 本次新建的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int backfillFromOpinions() {
        List<CaseLeaderOpinion> all = opinionMapper.selectList(
                new LambdaQueryWrapper<CaseLeaderOpinion>().orderByAsc(CaseLeaderOpinion::getId));
        int made = 0;
        for (CaseLeaderOpinion op : all) {
            CaseTodo before = todoMapper.selectOne(new LambdaQueryWrapper<CaseTodo>()
                    .eq(CaseTodo::getOpinionId, op.getId()).last("LIMIT 1"));
            if (before == null) {
                deriveFromOpinion(op);
                made++;
            }
        }
        return made;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 校验该案件是当前用户承办/协办的（复用统一口径，不另立权限） */
    private void requireMine(Long caseId) {
        if (AuthContext.isFullAccess()) {
            return;
        }
        java.util.Set<Long> mine = caseService.myVisibleCaseIds();
        if (mine == null || !mine.contains(caseId)) {
            throw new BizException(403, "只能操作本人相关案件的待办");
        }
    }

    private List<CaseTodoVO> toVOs(List<CaseTodo> list) {
        List<CaseTodoVO> out = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return out;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, CaseInfo> caseCache = new HashMap<>();
        for (CaseTodo t : list) {
            CaseTodoVO vo = toVO(t, false);
            if (vo.getCaseId() != null && !caseCache.containsKey(vo.getCaseId())) {
                CaseInfo c = caseMapper.selectById(vo.getCaseId());
                caseCache.put(vo.getCaseId(), c);
            }
            CaseInfo c = caseCache.get(vo.getCaseId());
            if (c != null) {
                vo.setCaseNo(c.getCaseNo());
                vo.setCaseName(c.getName());
            }
            // 距截止天数：负数=已超期，null=无期限
            if (t.getDeadline() != null) {
                vo.setDaysLeft((int) ChronoUnit.DAYS.between(now.toLocalDate(),
                        t.getDeadline().toLocalDate()));
            }
            out.add(vo);
        }
        return out;
    }

    /** 紧急程度排序权重：数字越大越紧急（降序即"高→低"） */
    private int urgencyWeight(String u) {
        if (CaseTodo.URG_URGENT.equals(u)) {
            return 3;
        }
        if (CaseTodo.URG_HIGH.equals(u)) {
            return 2;
        }
        return 1;   // NORMAL 与 NULL 都按一般
    }

    private int importanceWeight(String s) {
        if (CaseTodo.IMP_KEY.equals(s)) {
            return 3;
        }
        if (CaseTodo.IMP_MEDIUM.equals(s)) {
            return 2;
        }
        return 1;
    }

    /** 转 VO（含名称与权重） */
    private CaseTodoVO toVO(CaseTodo t, boolean withEvidence) {
        CaseTodoVO vo = new CaseTodoVO();
        vo.setId(t.getId());
        vo.setCaseId(t.getCaseId());
        vo.setOpinionId(t.getOpinionId());
        vo.setContent(t.getContent());
        vo.setStatus(t.getStatus());
        vo.setStatusName(statusName(t.getStatus()));
        vo.setSort(t.getSort());
        vo.setDoneAt(t.getDoneAt());
        vo.setRemark(t.getRemark());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setUpdatedAt(t.getUpdatedAt());
        // 分级字段：NULL 归一为"一般"，前端不用到处判空
        String u = StringUtils.hasText(t.getUrgency()) ? t.getUrgency() : CaseTodo.URG_NORMAL;
        String im = StringUtils.hasText(t.getImportance()) ? t.getImportance() : CaseTodo.IMP_NORMAL;
        vo.setUrgency(u);
        vo.setUrgencyName(urgencyName(u));
        vo.setImportance(im);
        vo.setImportanceName(importanceName(im));
        vo.setUrgencyWeight(urgencyWeight(u));
        vo.setImportanceWeight(importanceWeight(im));
        vo.setDeptSource(t.getDeptSource());
        vo.setDeadline(t.getDeadline());
        return vo;
    }

    private String urgencyName(String u) {
        if (CaseTodo.URG_URGENT.equals(u)) {
            return "紧急";
        }
        if (CaseTodo.URG_HIGH.equals(u)) {
            return "较急";
        }
        return "一般";
    }

    private String importanceName(String s) {
        if (CaseTodo.IMP_KEY.equals(s)) {
            return "重点";
        }
        if (CaseTodo.IMP_MEDIUM.equals(s)) {
            return "次重点";
        }
        return "一般";
    }

    private String statusName(String s) {
        if ("DONE".equals(s)) {
            return "已完成";
        }
        if ("CANCELLED".equals(s)) {
            return "已取消";
        }
        return "待办";
    }

    private CaseInfo requireCase(Long caseId) {
        CaseInfo c = caseId == null ? null : caseMapper.selectById(caseId);
        if (c == null) {
            throw new BizException("案件不存在");
        }
        return c;
    }

    private String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
