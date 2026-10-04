package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.CasePlan;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.flow.PoliceGroup;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.mapper.CasePlanMapper;
import com.caseflow.mapper.OrgEmployeeMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.security.Roles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 民警承办负荷详情（2026-10-04）。
 *
 * <p>用于盯办详情页点「主办人 / 协办人」查看该人的在办情况：
 * 正在主办/经办多少个案子、哪些快逾期、分别处在哪个阶段。
 *
 * <p><b>为什么单独一个类</b>：CaseService 已600+ 行，再塞"按人聚合统计"
 * 会让指派/流转这些核心逻辑更难读。这里只做只读聚合，不碰任何写操作。
 *
 * <p><b>权限</b>：能进案件详情的人都能查该案子的承办人负荷；
 * 但只返回统计数字与案件概要，**不泄露其他案件的案情细节**
 * （只给编号 + 名称 + 期限 + 阶段），避免横向信息泄露。
 */
@Service
public class StaffWorkloadService {

    /** 快逾期阈值（天）：距期限不足该天数算"快逾期" */
    public static final int DUE_SOON_DAYS = 3;

    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CasePlanMapper planMapper;
    @Resource
    private OrgEmployeeMapper employeeMapper;

    /**
     * 某员工的承办负荷详情。
     *
     * @param employeeId 员工 ID
     * @param caseId当前正在查看的案件（用于在返回里标出"当前案件"）
     */
    public Map<String, Object> workloadOf(Long employeeId, Long caseId) {
        Map<String, Object> m = new HashMap<>();
        OrgEmployee e = employeeId == null ? null : employeeMapper.selectById(employeeId);
        if (e == null) {
            m.put("found", false);
            return m;
        }
        m.put("found", true);
        m.put("employeeId", e.getId());
        m.put("name", e.getName());
        m.put("employeeNo", e.getEmployeeNo());
        m.put("dept", e.getDept());
        m.put("title", e.getTitle());
        m.put("phone", e.getPhone());
        String g = PoliceGroup.normalize(e.getPoliceGroup());
        m.put("policeGroup", g);
        m.put("policeGroupName", PoliceGroup.label(g));

        // 现役指派关系
        List<CaseAssignee> assigns = assigneeMapper.selectList(new LambdaQueryWrapper<CaseAssignee>()
                .eq(CaseAssignee::getEmployeeId, employeeId)
                .eq(CaseAssignee::getStatus, "ACTIVE"));
        List<Long> caseIds = assigns.stream().map(CaseAssignee::getCaseId)
                .collect(Collectors.toList());

        // 分主办/协办
        int ownerCount = (int) assigns.stream()
                .filter(a -> "OWNER".equals(a.getAssignRole())).count();
        m.put("ownerCount", ownerCount);
        m.put("memberCount", assigns.size() - ownerCount);
        m.put("totalCount", assigns.size());

        if (caseIds.isEmpty()) {
            m.put("cases", new ArrayList<>());
            m.put("dueSoonCount", 0);
            m.put("overdueCount", 0);
            m.put("initialCount", 0);
            m.put("detentionCount", 0);
            m.put("bailCount", 0);
            return m;
        }

        List<CaseInfo> cases = caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>()
                .in(CaseInfo::getId, caseIds));
        // 主办案件排前面，同类按期限近的排前面
        Set<Long> ownerIds = assigns.stream().filter(a -> "OWNER".equals(a.getAssignRole()))
                .map(CaseAssignee::getCaseId).collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now();
        int dueSoon = 0;
        int overdue = 0;
        int initial = 0;
        int detention = 0;
        int bail = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CaseInfo c : cases) {
            String mod = moduleOf(c);
            if ("INITIAL".equals(mod)) {
                initial++;
            } else if ("DETENTION".equals(mod)) {
                detention++;
            } else {
                bail++;
            }
            Integer days = c.getDeadline() == null ? null
                    : (int) ChronoUnit.DAYS.between(now.toLocalDate(), c.getDeadline().toLocalDate());
            boolean isOverdue = c.getDeadline() != null && c.getDeadline().isBefore(now);
            if (isOverdue) {
                overdue++;
            }
            if (c.getDeadline() != null && !c.getDeadline().isAfter(now.plusDays(DUE_SOON_DAYS))) {
                dueSoon++;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("caseId", c.getId());
            row.put("caseNo", c.getCaseNo());
            row.put("caseName", c.getName());
            row.put("caseType", c.getCaseType());
            row.put("caseTypeName", c.getCaseType());
            row.put("assignRole", ownerIds.contains(c.getId()) ? "OWNER" : "MEMBER");
            row.put("status", c.getStatus());
            row.put("module", mod);
            row.put("moduleName", moduleName(mod));
            row.put("deadline", c.getDeadline());
            row.put("daysLeft", days);
            row.put("overdue", isOverdue);
            row.put("dueSoon", c.getDeadline() != null && !c.getDeadline().isAfter(now.plusDays(DUE_SOON_DAYS)));
            row.put("planTotal", 0);
            row.put("planDone", 0);
            row.put("current", caseId != null && caseId.equals(c.getId()));
            rows.add(row);
        }
        // 主办优先 → 逾期优先 → 期限近的优先
        rows.sort((a, b) -> {
            int r1 = "OWNER".equals(a.get("assignRole")) ? 0 : 1;
            int r2 = "OWNER".equals(b.get("assignRole")) ? 0 : 1;
            if (r1 != r2) {
                return Integer.compare(r1, r2);
            }
            boolean o1 = Boolean.TRUE.equals(a.get("overdue"));
            boolean o2 = Boolean.TRUE.equals(b.get("overdue"));
            if (o1 != o2) {
                return o1 ? -1 : 1;
            }
            Long d1 = (Long) a.get("daysLeft");
            Long d2 = (Long) b.get("daysLeft");
            if (d1 == null && d2 == null) {
                return 0;
            }
            if (d1 == null) {
                return 1;   // 无期限沉底
            }
            if (d2 == null) {
                return -1;
            }
            return Long.compare(d1, d2);
        });
        m.put("cases", rows);
        m.put("dueSoonCount", dueSoon);
        m.put("overdueCount", overdue);
        m.put("initialCount", initial);
        m.put("detentionCount", detention);
        m.put("bailCount", bail);

        // 阶段进度汇总（每个案件已完成/总数），让"这人有几个案子做到哪一步"一目了然
        fillPlanProgress(rows);
        return m;
    }

    /** 批量取这些案件的阶段任务进度 */
    private void fillPlanProgress(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(r -> (Long) r.get("caseId")).collect(Collectors.toList());
        List<CasePlan> plans = planMapper.selectList(new LambdaQueryWrapper<CasePlan>()
                .in(CasePlan::getCaseId, ids));
        Map<Long, int[]> stat = new HashMap<>();
        for (CasePlan p : plans) {
            if ("CANCELLED".equals(p.getStatus())) {
                continue;
            }
            int[] arr = stat.computeIfAbsent(p.getCaseId(), k -> new int[2]);
            arr[0]++;                                   // total
            if ("DONE".equals(p.getStatus())) {
                arr[1]++;                               // done
            }
        }
        for (Map<String, Object> r : rows) {
            int[] arr = stat.get((Long) r.get("caseId"));
            r.put("planTotal", arr == null ? 0 : arr[0]);
            r.put("planDone", arr == null ? 0 : arr[1]);
        }
    }

    private String moduleOf(CaseInfo c) {
        String m = c.getCaseMeasure();
        if (!StringUtils.hasText(m) || "NONE".equals(m)) {
            return "INITIAL";
        }
        if ("DETENTION".equals(m)) {
            return "DETENTION";
        }
        if ("BAIL".equals(m) || "RESIDENCE".equals(m)) {
            return "BAIL_RESIDENCE";
        }
        return "INITIAL";
    }

    private String moduleName(String mod) {
        if ("DETENTION".equals(mod)) {
            return "刑拘在办";
        }
        if ("BAIL_RESIDENCE".equals(mod)) {
            return "取保及监居";
        }
        return "初查";
    }
}
