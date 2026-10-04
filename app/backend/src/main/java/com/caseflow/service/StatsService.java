package com.caseflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.caseflow.entity.CaseAssignee;
import com.caseflow.entity.CaseInfo;
import com.caseflow.entity.OrgEmployee;
import com.caseflow.mapper.CaseAssigneeMapper;
import com.caseflow.mapper.CaseInfoMapper;
import com.caseflow.support.DictHolder;
import com.caseflow.vo.StatsVO;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图表统计：全部在内存中按口径聚合，保证与列表页口径一致。
 */
@Service
public class StatsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 已结束状态：不计入「在手」口径 */
    private static final List<String> CLOSED = Arrays.asList("DONE", "CANCELLED");

    @Resource
    private CaseInfoMapper caseMapper;
    @Resource
    private CaseAssigneeMapper assigneeMapper;
    @Resource
    private EmployeeService employeeService;

    /** 到期桶（code / 名称），dueDist 与 dueByType 共用 */
    private static final String[] DUE_CODES = {"OVERDUE", "TODAY", "D3", "D7", "LATER", "NONE"};
    private static final String[] DUE_NAMES = {"已逾期", "今天到期", "3天内", "7天内", "更晚", "未设期限"};

    /**
     * 全量统计（不带筛选——保持旧调用行为不变）。
     */
    public StatsVO stats(int days) {
        return stats(days, null, null, null, null);
    }

    /**
     * 图表统计：维度间 AND、维度内 OR 的组合筛选。
     *
     * @param caseTypes  案卷类型多选（CRIMINAL/ADMINISTRATIVE/PRELIMINARY/CIVIL），空 = 全部
     * @param dueBuckets 到期桶多选（OVERDUE/TODAY/D3/D7/LATER/NONE），空 = 全部
     * @param priorities 优先级多选（URGENT/HIGH/NORMAL/LOW），空 = 全部
     */
    public StatsVO stats(int days, Set<String> caseTypes, Set<String> dueBuckets, Set<String> priorities) {
        return stats(days, caseTypes, dueBuckets, priorities, null);
    }

    /**
     * 带数据范围收敛的统计。
     *
     * @param restrictCaseIds 非 null 时只统计这些案件（普通民警传自己名下案件的 id），
     *                        null = 不限范围（管理层）。传空集合即"名下无案件"，各图表全为 0。
     */
    public StatsVO stats(int days, Set<String> caseTypes, Set<String> dueBuckets, Set<String> priorities,
                         Set<Long> restrictCaseIds) {
        int n = days <= 0 ? 14 : Math.min(days, 90);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        if (restrictCaseIds != null && restrictCaseIds.isEmpty()) {
            // 名下无案件：直接返回空口径，省掉一次全表扫描
            return emptyStats(n, now, caseTypes, dueBuckets, priorities);
        }

        List<CaseInfo> all = caseMapper.selectList(new LambdaQueryWrapper<CaseInfo>());
        if (restrictCaseIds != null) {
            all = all.stream().filter(c -> restrictCaseIds.contains(c.getId())).collect(Collectors.toList());
        }
        Map<Long, OrgEmployee> empMap = employeeService.employeeMap();

        // 维度间 AND、维度内 OR：任一维度为空 = 该维度不限制
        Set<String> ct = normalize(caseTypes);
        Set<String> db = normalize(dueBuckets);
        Set<String> pr = normalize(priorities);
        List<CaseInfo> filtered = all.stream()
                .filter(c -> ct.isEmpty() || ct.contains(c.getCaseType()))
                .filter(c -> pr.isEmpty() || pr.contains(c.getPriority()))
                .filter(c -> db.isEmpty() || db.contains(dueBucketOf(c, now)))
                .collect(Collectors.toList());

        StatsVO vo = new StatsVO();
        vo.setDays(n);
        vo.setGeneratedAt(now.format(FMT));
        vo.setAppliedCaseTypes(new ArrayList<>(ct));
        vo.setAppliedDueBuckets(new ArrayList<>(db));
        vo.setAppliedPriorities(new ArrayList<>(pr));
        // 所有口径都基于筛选后的数据集，保证多维组合筛选时全部联动
        vo.setTrend(trend(filtered, n, today, now));
        vo.setStatusDist(counter(filtered, c -> c.getStatus(), "STATUS",
                "PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED"));
        vo.setPriorityDist(counter(filtered, c -> c.getPriority(), "PRIORITY",
                "URGENT", "HIGH", "NORMAL", "LOW"));
        vo.setSourceDist(counter(filtered, c -> c.getSourceType(), "SOURCE_TYPE",
                "MANUAL", "PDF", "WORD", "EXCEL"));
        // 案卷类型分布：未设类型的存量案件不再出现在任何图表里（"未分类"已废弃）
        vo.setCaseTypeDist(counter(filtered, c -> c.getCaseType(), "CASE_TYPE",
                "PRELIMINARY", "CRIMINAL", "ADMINISTRATIVE").stream()
                .filter(d -> d.getCode() != null)
                .collect(Collectors.toList()));
        vo.setCategoryDist(categoryDist(filtered, 12));
        vo.setDueDist(dueDist(filtered, now));
        vo.setOverdueAgeDist(overdueAgeDist(filtered, now));
        vo.setUpcoming(upcoming(filtered, 7, today, now));
        vo.setTypeTrend(typeTrend(filtered, n, today));
        vo.setDueByType(dueByType(filtered, now));
        // 负载类口径同样跟随筛选联动：筛选「刑事」时承办人负载即各人的刑事案量
        vo.setDeptLoad(deptLoad(filtered, empMap, now));
        vo.setOwnerLoad(ownerLoad(filtered, empMap, now, 10));
        return vo;
    }

    /**
     * 名下无案件时的空口径：结构与正常返回完全一致，只是所有计数为 0，
     * 这样前端各图表能走同一套渲染逻辑（拿到空数组显示「暂无数据」），不用额外判空。
     */
    private StatsVO emptyStats(int n, LocalDateTime now, Set<String> caseTypes,
                               Set<String> dueBuckets, Set<String> priorities) {
        List<CaseInfo> none = Collections.emptyList();
        LocalDate today = now.toLocalDate();
        Set<String> ct = normalize(caseTypes);
        Set<String> db = normalize(dueBuckets);
        Set<String> pr = normalize(priorities);

        StatsVO vo = new StatsVO();
        vo.setDays(n);
        vo.setGeneratedAt(now.format(FMT));
        vo.setAppliedCaseTypes(new ArrayList<>(ct));
        vo.setAppliedDueBuckets(new ArrayList<>(db));
        vo.setAppliedPriorities(new ArrayList<>(pr));
        vo.setTrend(trend(none, n, today, now));
        vo.setStatusDist(counter(none, c -> c.getStatus(), "STATUS",
                "PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS", "DONE", "CANCELLED"));
        vo.setPriorityDist(counter(none, c -> c.getPriority(), "PRIORITY",
                "URGENT", "HIGH", "NORMAL", "LOW"));
        vo.setSourceDist(counter(none, c -> c.getSourceType(), "SOURCE_TYPE",
                "MANUAL", "PDF", "WORD", "EXCEL"));
        vo.setCaseTypeDist(counter(none, c -> c.getCaseType(), "CASE_TYPE",
                "PRELIMINARY", "CRIMINAL", "ADMINISTRATIVE").stream()
                .filter(d -> d.getCode() != null)
                .collect(Collectors.toList()));
        vo.setCategoryDist(categoryDist(none, 12));
        vo.setDueDist(dueDist(none, now));
        vo.setOverdueAgeDist(overdueAgeDist(none, now));
        vo.setUpcoming(upcoming(none, 7, today, now));
        vo.setTypeTrend(typeTrend(none, n, today));
        vo.setDueByType(dueByType(none, now));
        vo.setDeptLoad(deptLoad(none, Collections.emptyMap(), now));
        vo.setOwnerLoad(ownerLoad(none, Collections.emptyMap(), now, 10));
        return vo;
    }

    /** 去空白、去非法值；null 安全 */
    private Set<String> normalize(Set<String> in) {
        if (in == null) {
            return new HashSet<>();
        }
        return in.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** 案件归属的到期桶（与 dueDist 同一分桶规则） */
    private String dueBucketOf(CaseInfo c, LocalDateTime now) {
        if (CLOSED.contains(c.getStatus())) {
            return "CLOSED"; // 已办结/已撤销不属任何到期桶，被"未办结"语义排除
        }
        if (c.getDeadline() == null) {
            return "NONE";
        }
        if (c.getDeadline().isBefore(now)) {
            return "OVERDUE";
        }
        long d = ChronoUnit.DAYS.between(now.toLocalDate(), c.getDeadline().toLocalDate());
        if (d == 0) {
            return "TODAY";
        }
        if (d <= 3) {
            return "D3";
        }
        if (d <= 7) {
            return "D7";
        }
        return "LATER";
    }

    // ------------------------------------------------------------------
    // 各口径
    // ------------------------------------------------------------------

    /** 折线图：近 N 天每天新增 / 办结 */
    private List<StatsVO.TrendPoint> trend(List<CaseInfo> all, int n, LocalDate today, LocalDateTime now) {
        Map<String, StatsVO.TrendPoint> idx = new LinkedHashMap<>();
        for (int i = n - 1; i >= 0; i--) {
            String d = today.minusDays(i).toString();
            idx.put(d, new StatsVO.TrendPoint(d, 0, 0));
        }
        for (CaseInfo c : all) {
            if (c.getCreatedAt() != null) {
                StatsVO.TrendPoint p = idx.get(c.getCreatedAt().toLocalDate().toString());
                if (p != null) {
                    p.setCreated(p.getCreated() + 1);
                }
            }
            if ("DONE".equals(c.getStatus()) && c.getUpdatedAt() != null) {
                StatsVO.TrendPoint p = idx.get(c.getUpdatedAt().toLocalDate().toString());
                if (p != null) {
                    p.setDone(p.getDone() + 1);
                }
            }
        }
        return new ArrayList<>(idx.values());
    }

    /** 直方图：未来 N 天每天到期量（含今天，逾期单独归类） */
    private List<StatsVO.TrendPoint> upcoming(List<CaseInfo> all, int n, LocalDate today, LocalDateTime now) {
        Map<String, StatsVO.TrendPoint> idx = new LinkedHashMap<>();
        idx.put("overdue", new StatsVO.TrendPoint("已逾期", 0, 0));
        for (int i = 0; i < n; i++) {
            String d = today.plusDays(i).toString();
            idx.put(d, new StatsVO.TrendPoint(d, 0, 0));
        }
        for (CaseInfo c : all) {
            if (CLOSED.contains(c.getStatus()) || c.getDeadline() == null) {
                continue;
            }
            String key;
            if (c.getDeadline().isBefore(now)) {
                key = "overdue";
            } else {
                key = c.getDeadline().toLocalDate().toString();
            }
            StatsVO.TrendPoint p = idx.get(key);
            if (p != null) {
                p.setCreated(p.getCreated() + 1);
                if (c.getDeadline().isBefore(now)) {
                    p.setDone(p.getDone() + 1);
                }
            }
        }
        return new ArrayList<>(idx.values());
    }

    /** 直方图：到期分布 */
    private List<StatsVO.NameValue> dueDist(List<CaseInfo> all, LocalDateTime now) {
        Map<String, StatsVO.NameValue> m = new LinkedHashMap<>();
        for (int i = 0; i < DUE_CODES.length; i++) {
            m.put(DUE_CODES[i], new StatsVO.NameValue(DUE_CODES[i], DUE_NAMES[i], 0));
        }
        for (CaseInfo c : all) {
            // 分桶统一走 dueBucketOf，避免同一规则在三处各写一份后口径漂移
            String bucket = dueBucketOf(c, now);
            if ("CLOSED".equals(bucket)) {
                continue;
            }
            bump(m, bucket);
        }
        return new ArrayList<>(m.values());
    }

    /**
     * 堆叠趋势：近 N 天每天新增按案卷类型拆分。
     * 未设类型的案件计入 criminal=false 的 noneType？——不，类型四类之外（含 null）统一记入 preliminary 前的空档：
     * 这里把 null 类型排除在堆叠外（与环形图「未分类」一致由 caseTypeDist 呈现），避免口径混乱。
     */
    private List<StatsVO.TypeTrendPoint> typeTrend(List<CaseInfo> all, int n, LocalDate today) {
        Map<String, StatsVO.TypeTrendPoint> idx = new LinkedHashMap<>();
        for (int i = n - 1; i >= 0; i--) {
            String d = today.minusDays(i).toString();
            idx.put(d, new StatsVO.TypeTrendPoint(d));
        }
        for (CaseInfo c : all) {
            if (c.getCreatedAt() == null || c.getCaseType() == null) {
                continue;
            }
            StatsVO.TypeTrendPoint p = idx.get(c.getCreatedAt().toLocalDate().toString());
            if (p == null) {
                continue;
            }
            switch (c.getCaseType()) {
                case "CRIMINAL":
                    p.setCriminal(p.getCriminal() + 1);
                    break;
                case "ADMINISTRATIVE":
                    p.setAdministrative(p.getAdministrative() + 1);
                    break;
                case "PRELIMINARY":
                    p.setPreliminary(p.getPreliminary() + 1);
                    break;
                default:
                    break;
            }
        }
        return new ArrayList<>(idx.values());
    }

    /** 堆叠矩阵：每个到期桶内按案卷类型拆分计数（含未设类型 noneType，前端以灰色呈现） */
    private List<StatsVO.DueBucketByType> dueByType(List<CaseInfo> all, LocalDateTime now) {
        Map<String, StatsVO.DueBucketByType> m = new LinkedHashMap<>();
        for (int i = 0; i < DUE_CODES.length; i++) {
            m.put(DUE_CODES[i], new StatsVO.DueBucketByType(DUE_CODES[i], DUE_NAMES[i]));
        }
        for (CaseInfo c : all) {
            String bucket = dueBucketOf(c, now);
            if ("CLOSED".equals(bucket)) {
                continue;
            }
            StatsVO.DueBucketByType row = m.get(bucket);
            if (row == null) {
                continue;
            }
            String t = c.getCaseType();
            if (t == null) {
                continue; // 未设类型的存量案件不计入堆叠矩阵（"未分类"已废弃）
            }
            if ("CRIMINAL".equals(t)) {
                row.setCriminal(row.getCriminal() + 1);
            } else if ("ADMINISTRATIVE".equals(t)) {
                row.setAdministrative(row.getAdministrative() + 1);
            } else if ("PRELIMINARY".equals(t)) {
                row.setPreliminary(row.getPreliminary() + 1);
            } else {
                row.setNoneType(row.getNoneType() + 1);
            }
        }
        return new ArrayList<>(m.values());
    }

    /** 直方图：案件类别（小类 / 案由）分布，只取数量最多的前 N 类 */
    private List<StatsVO.NameValue> categoryDist(List<CaseInfo> all, int topN) {
        Map<String, StatsVO.NameValue> m = new HashMap<>();
        for (CaseInfo c : all) {
            String cat = c.getCategory();
            if (cat == null || cat.trim().isEmpty()) {
                continue;
            }
            StatsVO.NameValue nv = m.computeIfAbsent(cat, k -> new StatsVO.NameValue(k, k, 0));
            nv.setValue(nv.getValue() + 1);
        }
        return m.values().stream()
                .sorted(Comparator.comparingLong(StatsVO.NameValue::getValue).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /** 直方图：逾期账龄 */
    private List<StatsVO.NameValue> overdueAgeDist(List<CaseInfo> all, LocalDateTime now) {
        Map<String, StatsVO.NameValue> m = new LinkedHashMap<>();
        m.put("A", new StatsVO.NameValue("A", "逾期 1-3 天", 0));
        m.put("B", new StatsVO.NameValue("B", "逾期 4-7 天", 0));
        m.put("C", new StatsVO.NameValue("C", "逾期 8-14 天", 0));
        m.put("D", new StatsVO.NameValue("D", "逾期 15 天以上", 0));
        LocalDate today = now.toLocalDate();
        for (CaseInfo c : all) {
            if (CLOSED.contains(c.getStatus()) || c.getDeadline() == null || !c.getDeadline().isBefore(now)) {
                continue;
            }
            long d = Math.abs(ChronoUnit.DAYS.between(today, c.getDeadline().toLocalDate()));
            if (d <= 3) {
                bump(m, "A");
            } else if (d <= 7) {
                bump(m, "B");
            } else if (d <= 14) {
                bump(m, "C");
            } else {
                bump(m, "D");
            }
        }
        return new ArrayList<>(m.values());
    }

    /** 直方图：部门在手案件负载 */
    private List<StatsVO.NameValue> deptLoad(List<CaseInfo> all, Map<Long, OrgEmployee> empMap, LocalDateTime now) {
        Map<Long, CaseInfo> openCases = all.stream()
                .filter(c -> !CLOSED.contains(c.getStatus()))
                .collect(Collectors.toMap(CaseInfo::getId, c -> c, (a, b) -> a));
        List<CaseAssignee> actives = assigneeMapper.selectList(
                new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getStatus, "ACTIVE"));

        Map<String, StatsVO.NameValue> m = new HashMap<>();
        // 同一部门内一个案件只算一次（主办与协办同部门时不重复计数）
        Set<String> counted = new HashSet<>();
        for (CaseAssignee a : actives) {
            if (!openCases.containsKey(a.getCaseId())) {
                continue;
            }
            OrgEmployee e = empMap.get(a.getEmployeeId());
            String dept = (e == null || e.getDept() == null || e.getDept().isEmpty()) ? "未分配" : e.getDept();
            String key = dept + "#" + a.getCaseId();
            if (!counted.add(key)) {
                continue;
            }
            StatsVO.NameValue nv = m.computeIfAbsent(dept, k -> new StatsVO.NameValue(k, k, 0));
            nv.setValue(nv.getValue() + 1);
            CaseInfo c = openCases.get(a.getCaseId());
            if (c.getDeadline() != null && c.getDeadline().isBefore(now)) {
                nv.setOverdue(nv.getOverdue() + 1);
            }
        }
        return m.values().stream()
                .sorted(Comparator.comparingLong(StatsVO.NameValue::getValue).reversed())
                .collect(Collectors.toList());
    }

    /** 直方图：承办人在手负载（含逾期数） */
    private List<StatsVO.NameValue> ownerLoad(List<CaseInfo> all, Map<Long, OrgEmployee> empMap,
                                              LocalDateTime now, int topN) {
        Map<Long, CaseInfo> openCases = all.stream()
                .filter(c -> !CLOSED.contains(c.getStatus()))
                .collect(Collectors.toMap(CaseInfo::getId, c -> c, (a, b) -> a));
        List<CaseAssignee> actives = assigneeMapper.selectList(
                new LambdaQueryWrapper<CaseAssignee>().eq(CaseAssignee::getStatus, "ACTIVE"));

        Map<Long, StatsVO.NameValue> m = new HashMap<>();
        for (CaseAssignee a : actives) {
            if (!openCases.containsKey(a.getCaseId())) {
                continue;
            }
            StatsVO.NameValue nv = m.computeIfAbsent(a.getEmployeeId(), k -> {
                OrgEmployee e = empMap.get(k);
                return new StatsVO.NameValue(String.valueOf(k), e == null ? "未知" : e.getName(), 0);
            });
            nv.setValue(nv.getValue() + 1);
            CaseInfo c = openCases.get(a.getCaseId());
            if (c.getDeadline() != null && c.getDeadline().isBefore(now)) {
                nv.setOverdue(nv.getOverdue() + 1);
            }
        }
        return m.values().stream()
                .sorted(Comparator.comparingLong(StatsVO.NameValue::getValue)
                        .thenComparing(StatsVO.NameValue::getOverdue).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------

    private List<StatsVO.NameValue> counter(List<CaseInfo> all, java.util.function.Function<CaseInfo, String> getter,
                                            String dictType, String... codes) {
        Map<String, StatsVO.NameValue> m = new LinkedHashMap<>();
        for (String code : codes) {
            m.put(code, new StatsVO.NameValue(code, DictHolder.name(dictType, code), 0));
        }
        for (CaseInfo c : all) {
            String code = getter.apply(c);
            StatsVO.NameValue nv = m.get(code);
            if (nv == null) {
                nv = new StatsVO.NameValue(code, code == null ? "未分类" : code, 0);
                m.put(code, nv);
            }
            nv.setValue(nv.getValue() + 1);
        }
        return new ArrayList<>(m.values());
    }

    private void bump(Map<String, StatsVO.NameValue> m, String code) {
        StatsVO.NameValue nv = m.get(code);
        if (nv != null) {
            nv.setValue(nv.getValue() + 1);
        }
    }
}
