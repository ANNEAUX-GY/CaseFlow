package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.common.PageResult;
import com.caseflow.dto.CaseQuery;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.CaseTodo;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.mapper.CaseTodoMapper;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.StatsVO;
import com.caseflow.vo.WatchBoardVO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 案件盯办：三子模块列表组装（含计划进度）+ 看板统计 + 预警计数。
 */
@Service
public class WatchService {

    private static final List<String> CLOSED = java.util.Arrays.asList("DONE", "CANCELLED");

    @Resource
    private CaseService caseService;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private CaseTodoMapper todoMapper;
    @Resource
    private com.caseflow.mapper.CaseTodoFeedbackMapper feedbackMapper;

    /** 盯办列表 = 通用分页查询 + 计划进度填充（done/total/overdue） */
    public PageResult<CaseVO> page(CaseQuery query) {
        PageResult<CaseVO> r = caseService.page(query);
        List<CaseVO> list = r.getList();
        if (list == null || list.isEmpty()) {
            return r;
        }
        Map<Long, PlanStat> stats = planStats(list.stream().map(CaseVO::getId).collect(Collectors.toList()));
        List<Long> ids = list.stream().map(CaseVO::getId).collect(Collectors.toList());
        Map<Long, TodoStat> todoStats = todoStats(ids, list);
        Map<Long, Integer> fbCounts = feedbackCounts(ids);
        LocalDateTime now = LocalDateTime.now();
        for (CaseVO vo : list) {
            PlanStat st = stats.get(vo.getId());
            vo.setPlanDone(st == null ? 0 : st.done);
            vo.setPlanTotal(st == null ? 0 : st.total);
            vo.setPlanOverdue(st == null ? 0 : (int) st.overdueOf(now));
            // 流程阶段随列表一起给出，前端不必再逐行拉 flow/progress
            String fs = vo.getFlowStage() == null || vo.getFlowStage().trim().isEmpty()
                    ? com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL
                    : vo.getFlowStage();
            if ("ADMINISTRATIVE".equals(vo.getCaseType())
                    && !com.caseflow.flow.CaseFlowTemplate.STAGE_CLOSED.equals(fs)) {
                fs = com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL;
            }
            vo.setFlowStage(fs);
            vo.setFlowStageName(com.caseflow.flow.CaseFlowTemplate.stageLabel(fs));
            // 阶段进度口径 = 该阶段**案件待办**的完成情况（2026-10-12）。
            // 列表口径必须与 FlowService.progressOf 完全一致，否则会出现
            // "列表写 100%、点进抽屉写 60%"这种没法解释的错位。
            TodoStat td = todoStats.get(vo.getId());
            int fDone = td == null ? 0 : td.done;
            int fTotal = td == null ? 0 : td.total;
            vo.setFlowDone(fDone);
            vo.setFlowTotal(fTotal);
            vo.setTodoDone(fDone);
            vo.setTodoTotal(fTotal);
            // 阶段做完不自动跳（出口要人拍板），但必须让领导看得见「这个案子在等你确认流转」
            vo.setFlowFinished(!"ADMINISTRATIVE".equals(vo.getCaseType()) && fTotal > 0 && fDone == fTotal
                    && !com.caseflow.flow.CaseFlowTemplate.STAGE_CLOSED.equals(fs));
            // 初查状态四态（2026-10-14）：未分配 → 已分配 → 已签收 → 已反馈。
            // 与 CaseVO.investStage 注释里的判定链保持一致
            int fb = fbCounts.getOrDefault(vo.getId(), 0);
            vo.setFeedbackCount(fb);
            if (vo.getOwner() == null) {
                vo.setInvestStage("UNASSIGNED");
            } else if (fb > 0) {
                vo.setInvestStage("FEEDBACK");
            } else if (fTotal > 0) {
                vo.setInvestStage("RECEIVED");
            } else {
                vo.setInvestStage("ASSIGNED");
            }
        }
        return r;
    }

    /** 批量反馈计数：按案件 id 一次 group by，避免逐案查库 */
    private Map<Long, Integer> feedbackCounts(List<Long> caseIds) {
        Map<Long, Integer> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }
        List<com.caseflow.entity.CaseTodoFeedback> all = feedbackMapper.selectList(
                new LambdaQueryWrapper<com.caseflow.entity.CaseTodoFeedback>()
                        .in(com.caseflow.entity.CaseTodoFeedback::getCaseId, caseIds));
        for (com.caseflow.entity.CaseTodoFeedback f : all) {
            map.merge(f.getCaseId(), 1, Integer::sum);
        }
        return map;
    }

    /**
     * 看板四组计数。
     *
     * <p><b>按案件类型过滤</b>（2026-10 统一类型选择器）：看板卡片与下方列表必须同口径，
     * 否则用户选中「刑事案件」却看到行政案件的计数。caseType 口径与
     * {@link CaseService#page} 一致：OTHER = 非刑事、非行政（含未立案与空值）。
     */
    public WatchBoardVO board(String caseType) {
        return board(caseType, null, false);
    }

    public WatchBoardVO board(String caseType, String category) {
        return board(caseType, category, false);
    }

    /**
     * 盯办看板四组计数 + 图表数据。
     *
     * <p><b>按案件类型过滤</b>（2026-10 统一类型选择器）：看板卡片与下方列表必须同口径，
     * 否则用户选中「刑事案件」却看到行政案件的计数。caseType 口径与
     * {@link CaseService#page} 一致：OTHER = 非刑事、非行政（含未立案与空值）。
     *
     * <p><b>按小类过滤</b>（2026-10-09 三级浏览）：从「按类别浏览」选完小类进来时，
     * 卡片若还按大类统计，就会出现"卡片写 7 件、下面列表只 2 条"的口径错位——
     * 所以卡片与图表统统走 category；口径与 {@link CaseService#page} 一致：NONE = 类别为空。
     * 唯一例外是 {@code categoryDist}（各小类分布），见该字段注释。
     *
     * <p><b>按重点关注过滤</b>（2026-10-10）：列表勾了「只看重点」，卡片/图表也必须跟着收，
     * 否则又回到"卡片写 10 件、下面列表只 1 条"的老毛病。focus 口径与
     * {@link CaseService#page} 一致：**等于 1** 才算（focus 列 NOT NULL DEFAULT 0，
     * 但存量行可能是 NULL，写 &lt;&gt;0 会把历史数据误算成重点）。
     */
    public WatchBoardVO board(String caseType, String category, boolean focusOnly) {
        LocalDateTime now = LocalDateTime.now();
        WatchBoardVO vo = new WatchBoardVO();

        // 类型过滤走 Lambda 条件（口径与 CaseService.page 一致：OTHER = 非刑事非行政含空值），
        // 不拼 SQL 字符串——拼接会把前端入参直接带进查询，存在注入风险
        LambdaQueryWrapper<CaseInfo> typeW = new LambdaQueryWrapper<>();
        if (caseType != null && !caseType.trim().isEmpty()) {
            String ct = caseType.trim();
            if ("OTHER".equalsIgnoreCase(ct)) {
                typeW.and(w -> w.notIn(CaseInfo::getCaseType, "CRIMINAL", "ADMINISTRATIVE")
                        .or().isNull(CaseInfo::getCaseType));
            } else {
                typeW.eq(CaseInfo::getCaseType, ct);
            }
        }
        List<CaseInfo> all = caseMapper.selectList(typeW);

        // 小类筛选：NONE = 未分类（NULL 或空串），与 CaseService.page 同一口径。
        // 在内存里筛而不是再查一次库：本表是内网小数据量（在办案件量级几十），
        // 少一次往返比省这点内存更值；也让「大类口径」的 all 天然可复用给 categoryDist。
        List<CaseInfo> scoped = all;
        if (category != null && !category.trim().isEmpty()) {
            String cat = category.trim();
            scoped = scoped.stream()
                    .filter(c -> "NONE".equalsIgnoreCase(cat)
                            ? isBlank(c.getCategory())
                            : cat.equals(c.getCategory()))
                    .collect(Collectors.toList());
        }
        // 只看重点：放在 category 之后、分桶之前，卡片与列表共用同一份 scoped。
        // 与 CaseService.page 的口径严格对齐：focus 等于 1（NULL 不算）。
        if (focusOnly) {
            scoped = scoped.stream()
                    .filter(c -> c.getFocus() != null && c.getFocus() == 1)
                    .collect(Collectors.toList());
        }

        List<CaseInfo> open = scoped.stream().filter(c -> !CLOSED.contains(c.getStatus())).collect(Collectors.toList());

        // 三张卡片的分桶口径统一走 PoliceGroup：哪条措施归哪个子模块只有那一处定义。
        // 2026-10-09 新增「拘传 / 逮捕」后，写死 DETENTION / BAIL / RESIDENCE 会让新措施三张卡片都数不到。
        String initialModule = com.caseflow.flow.PoliceGroup.moduleOfMeasure(null);
        List<String> detentionMeasures = com.caseflow.flow.PoliceGroup.measuresOfModule("DETENTION");
        List<String> bailMeasures = com.caseflow.flow.PoliceGroup.measuresOfModule("BAIL_RESIDENCE");

        // 初查：无强制措施（含拘传这类不改变在押状态的）+ 在办
        List<CaseInfo> initial = open.stream()
                .filter(c -> "ADMINISTRATIVE".equals(c.getCaseType())
                        || initialModule.equals(com.caseflow.flow.PoliceGroup.moduleOfMeasure(c.getCaseMeasure())))
                .collect(Collectors.toList());
        vo.setInitialTotal(initial.size());
        vo.setInitialWithSuspect(countWithSuspect(initial.stream().map(CaseInfo::getId).collect(Collectors.toList())));

        // 刑拘在办（拘留 / 逮捕）/ 取保监居
        List<CaseInfo> detention = open.stream()
                .filter(c -> !"ADMINISTRATIVE".equals(c.getCaseType())
                        && c.getCaseMeasure() != null && detentionMeasures.contains(c.getCaseMeasure()))
                .collect(Collectors.toList());
        List<CaseInfo> bail = open.stream()
                .filter(c -> !"ADMINISTRATIVE".equals(c.getCaseType())
                        && c.getCaseMeasure() != null && bailMeasures.contains(c.getCaseMeasure()))
                .collect(Collectors.toList());
        vo.setDetentionTotal(detention.size());
        vo.setBailTotal(bail.size());
        vo.setDetentionDueSoon(detention.stream().filter(c -> inDays(c.getDetainDeadline(), now, 7)).count());
        vo.setDetentionOverdue(detention.stream().filter(c -> c.getDetainDeadline() != null
                && c.getDetainDeadline().isBefore(now)).count());
        vo.setBailDueSoon(bail.stream().filter(c -> inDays(c.getDetainDeadline(), now, 30)).count());

        // 计划逾期（W2）按子模块计入初查卡片
        Map<Long, PlanStat> stats = planStats(initial.stream().map(CaseInfo::getId).collect(Collectors.toList()));
        vo.setInitialPlanOverdue(stats.values().stream().filter(s -> s.overdueOf(now) > 0).count());
        // 待审批（W7 滞留 ≥2 天）
        List<CaseInfo> approving = open.stream()
                .filter(c -> "PENDING_APPROVAL".equals(c.getInvestigationStatus())).collect(Collectors.toList());
        vo.setApprovalTotal(approving.size());
        LocalDateTime staleBefore = now.minusDays(2);
        vo.setApprovalStale(approving.stream().filter(c -> c.getUpdatedAt() != null
                && c.getUpdatedAt().isBefore(staleBefore)).count());

        // ---- 图表：与上面卡片同源同一次查询，杜绝"卡片 7 件、图里 6 件" ----
        vo.setCategoryDist(categoryDist(
                all.stream().filter(c -> !CLOSED.contains(c.getStatus())).collect(Collectors.toList())));
        vo.setDueDist(dueDist(detention, bail, now));

        return vo;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 在办案件按小类统计，条数降序；「未分类」固定垫底。
     *
     * <p>条数降序而不是按字典顺序：这张图是拿来横向比较"哪类多、哪类少"的，
     * 长条在上更省眼；垫底未分类则是因为它是个兜底桶，不该挤在真实类别中间。
     */
    private List<StatsVO.NameValue> categoryDist(List<CaseInfo> open) {
        Map<String, Long> cnt = new LinkedHashMap<>();
        for (CaseInfo c : open) {
            cnt.merge(isBlank(c.getCategory()) ? "" : c.getCategory().trim(), 1L, Long::sum);
        }
        List<StatsVO.NameValue> list = new ArrayList<>();
        cnt.forEach((k, v) -> list.add(k.isEmpty()
                ? new StatsVO.NameValue("NONE", "未分类", v)
                : new StatsVO.NameValue(k, k, v)));
        list.sort((a, b) -> {
            boolean an = "NONE".equals(a.getCode());
            boolean bn = "NONE".equals(b.getCode());
            if (an != bn) {
                return an ? 1 : -1;
            }
            return Long.compare(b.getValue(), a.getValue());
        });
        return list;
    }

    /** 已采取措施案件的期限余量分桶；分界与卡片上的「临期（≤7天）」口径一致 */
    private List<StatsVO.NameValue> dueDist(List<CaseInfo> detention, List<CaseInfo> bail, LocalDateTime now) {
        List<CaseInfo> withMeasure = new ArrayList<>(detention);
        withMeasure.addAll(bail);
        long overdue = 0, d7 = 0, d30 = 0, later = 0, unset = 0;
        for (CaseInfo c : withMeasure) {
            LocalDateTime dl = c.getDetainDeadline();
            if (dl == null) {
                unset++;
            } else if (dl.isBefore(now)) {
                overdue++;
            } else if (dl.isBefore(now.plusDays(7))) {
                d7++;
            } else if (dl.isBefore(now.plusDays(30))) {
                d30++;
            } else {
                later++;
            }
        }
        List<StatsVO.NameValue> list = new ArrayList<>();
        list.add(new StatsVO.NameValue("OVERDUE", "已超期", overdue));
        list.add(new StatsVO.NameValue("D7", "7天内到期", d7));
        list.add(new StatsVO.NameValue("D30", "8-30天", d30));
        list.add(new StatsVO.NameValue("LATER", "30天以上", later));
        list.add(new StatsVO.NameValue("UNSET", "未登记期限", unset));
        return list;
    }

    /** 期限在 (now, now+days] 区间内（临期未超期） */
    private boolean inDays(LocalDateTime deadline, LocalDateTime now, int days) {
        return deadline != null && !deadline.isBefore(now) && deadline.isBefore(now.plusDays(days));
    }

    private long countWithSuspect(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        Long cnt = caseMapper.selectCount(new LambdaQueryWrapper<CaseInfo>()
                .in(CaseInfo::getId, caseIds)
                .apply("EXISTS (SELECT 1 FROM case_suspect s WHERE s.case_id = case_info.id)"));
        return cnt == null ? 0 : cnt;
    }

    /**
     * 批量计划统计：total 只计 待完成+已完成（取消的不进分母）；overdue = 待完成且已过计划时限。
     *
     * <p>case_plan 在 2026-10-12 之后<b>不再承担阶段任务</b>——办案阶段统一由
     * 「案件待办」承担，这里回归它本来的角色：民警自排的工作计划与逾期预警，
     * 因此<b>不再按 flow_stage 过滤</b>（否则民警在计划里写的事会随阶段流转凭空消失）。
     */
    private Map<Long, PlanStat> planStats(List<Long> caseIds) {
        Map<Long, PlanStat> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }

        List<CasePlan> plans = planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .in(CasePlan::getCaseId, caseIds));
        for (CasePlan p : plans) {
            if ("CANCELLED".equals(p.getStatus())) {
                continue;
            }
            PlanStat stat = map.computeIfAbsent(p.getCaseId(), k -> new PlanStat());
            stat.total++;
            if ("DONE".equals(p.getStatus())) {
                stat.done++;
            } else if (p.getPlannedAt() != null) {
                stat.pendingDeadlines.add(p.getPlannedAt());
            }
        }
        return map;
    }

    /**
     * 批量待办统计（2026-10-12）：按案件<b>当前阶段</b>归集。
     *
     * <p><b>为什么不用 planStats</b>：阶段任务已经统一到「案件待办」（case_todo），
     * case_plan 回到它本来的角色——民警自己排的工作计划（含计划时限与逾期）。
     * 两套口径分工明确：planStats 管「计划逾期预警」，todoStats 管「阶段进度」。
     *
     * <p><b>归位口径必须与 FlowService.listTodosOfStage 一致</b>：
     * 只算顶层主任务（子任务是细节）、排除 CANCELLED、
     * flow_stage 为 NULL 的存量待办归入案件当前阶段。
     */
    private Map<Long, TodoStat> todoStats(List<Long> caseIds, List<CaseVO> voList) {
        Map<Long, TodoStat> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }
        // 每案当前阶段（flow_stage 空 → INITIAL）
        Map<Long, String> stageOfCase = new HashMap<>();
        for (CaseVO vo : voList) {
            String s = vo.getFlowStage() == null || vo.getFlowStage().trim().isEmpty()
                    ? com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL
                    : vo.getFlowStage();
            stageOfCase.put(vo.getId(), s);
        }
        List<CaseTodo> all = todoMapper.selectList(new LambdaQueryWrapper<CaseTodo>()
                .in(CaseTodo::getCaseId, caseIds)
                .isNull(CaseTodo::getParentId));
        for (CaseTodo t : all) {
            if ("CANCELLED".equals(t.getStatus())) {
                continue;
            }
            String cur = stageOfCase.get(t.getCaseId());
            if (cur == null) {
                continue;
            }
            String ts = t.getFlowStage() == null || t.getFlowStage().trim().isEmpty()
                    ? cur : t.getFlowStage();
            if (!cur.equals(ts)) {
                continue;   // 属于历史阶段，不计入当前阶段进度
            }
            TodoStat stat = map.computeIfAbsent(t.getCaseId(), k -> new TodoStat());
            stat.total++;
            if ("DONE".equals(t.getStatus())) {
                stat.done++;
            }
        }
        return map;
    }

    /** 待完成且有计划时限的时间点集合；输出时按当前时间过滤出逾期数 */
    private static class PlanStat {
        int done;
        int total;
        java.util.List<LocalDateTime> pendingDeadlines = new java.util.ArrayList<>();

        long overdueOf(LocalDateTime now) {
            return pendingDeadlines.stream().filter(t -> t.isBefore(now)).count();
        }
    }

    /** 阶段待办统计：当前阶段的待办完成情况（2026-10-12） */
    private static class TodoStat {
        int done;
        int total;
    }
}
