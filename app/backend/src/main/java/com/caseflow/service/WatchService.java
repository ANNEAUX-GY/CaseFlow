package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.common.PageResult;
import com.caseflow.dto.CaseQuery;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.vo.CaseVO;
import com.caseflow.vo.WatchBoardVO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
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

    /** 盯办列表 = 通用分页查询 + 计划进度填充（done/total/overdue） */
    public PageResult<CaseVO> page(CaseQuery query) {
        PageResult<CaseVO> r = caseService.page(query);
        List<CaseVO> list = r.getList();
        if (list == null || list.isEmpty()) {
            return r;
        }
        Map<Long, PlanStat> stats = planStats(list.stream().map(CaseVO::getId).collect(Collectors.toList()));
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
            vo.setFlowStage(fs);
            vo.setFlowStageName(com.caseflow.flow.CaseFlowTemplate.stageLabel(fs));
        }
        return r;
    }

    /** 看板四组计数 */
    public WatchBoardVO board() {
        LocalDateTime now = LocalDateTime.now();
        WatchBoardVO vo = new WatchBoardVO();

        List<CaseInfo> all = caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>());
        List<CaseInfo> open = all.stream().filter(c -> !CLOSED.contains(c.getStatus())).collect(Collectors.toList());

        // 初查：无强制措施 + 在办
        List<CaseInfo> initial = open.stream()
                .filter(c -> c.getCaseMeasure() == null || "NONE".equals(c.getCaseMeasure()))
                .collect(Collectors.toList());
        vo.setInitialTotal(initial.size());
        vo.setInitialWithSuspect(countWithSuspect(initial.stream().map(CaseInfo::getId).collect(Collectors.toList())));

        // 刑拘在办 / 取保监居
        List<CaseInfo> detention = open.stream()
                .filter(c -> "DETENTION".equals(c.getCaseMeasure())).collect(Collectors.toList());
        List<CaseInfo> bail = open.stream()
                .filter(c -> "BAIL".equals(c.getCaseMeasure()) || "RESIDENCE".equals(c.getCaseMeasure()))
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

        return vo;
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
     * <p><b>按流程阶段过滤</b>（2026-10）：只统计案件<strong>当前阶段</strong>的任务。
     * 阶段流转时旧阶段任务会置 CANCELLED 已天然排除，但存量计划（stage 为 NULL）
     * 若不按阶段归位，会在案件流转到「刑拘在办」后仍被计入分母，
     * 导致进度虚高、且与流程面板显示的数字对不上。
     * 这里统一用「stage 为 NULL 视为 INITIAL」的同一套归位口径，与 FlowService 保持一致。
     */
    private Map<Long, PlanStat> planStats(List<Long> caseIds) {
        Map<Long, PlanStat> map = new HashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return map;
        }
        // 取每案的当前阶段，用于过滤
        Map<Long, String> stageOfCase = new HashMap<>();
        for (CaseInfo c : caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>()
                .select(CaseInfo::getId, CaseInfo::getFlowStage)
                .in(CaseInfo::getId, caseIds))) {
            stageOfCase.put(c.getId(),
                    c.getFlowStage() == null || c.getFlowStage().trim().isEmpty()
                            ? com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL
                            : c.getFlowStage());
        }

        List<CasePlan> plans = planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .in(CasePlan::getCaseId, caseIds));
        for (CasePlan p : plans) {
            if ("CANCELLED".equals(p.getStatus())) {
                continue;
            }
            // 只算当前阶段的任务：stage 为 NULL 的存量归初查
            String st = p.getStage() == null || p.getStage().trim().isEmpty()
                    ? com.caseflow.flow.CaseFlowTemplate.STAGE_INITIAL
                    : p.getStage();
            String cur = stageOfCase.get(p.getCaseId());
            if (cur == null || !cur.equals(st)) {
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

    /** 待完成且有计划时限的时间点集合；输出时按当前时间过滤出逾期数 */
    private static class PlanStat {
        int done;
        int total;
        java.util.List<LocalDateTime> pendingDeadlines = new java.util.ArrayList<>();

        long overdueOf(LocalDateTime now) {
            return pendingDeadlines.stream().filter(t -> t.isBefore(now)).count();
        }
    }
}
