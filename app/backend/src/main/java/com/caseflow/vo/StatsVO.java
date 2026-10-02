package com.caseflow.vo;

import lombok.Data;

import java.util.List;

/**
 * 可视化统计：一个接口返回各页面需要的全部图表数据。
 */
@Data
public class StatsVO {

    /** 统计口径：统计天数 / 生成时间 */
    private int days;
    private String generatedAt;

    /** 折线图：近 N 天新增 / 办结 */
    private List<TrendPoint> trend;

    /** 直方图：状态分布 */
    private List<NameValue> statusDist;

    /** 直方图：优先级分布 */
    private List<NameValue> priorityDist;

    /** 直方图：来源类型分布 */
    private List<NameValue> sourceDist;

    /** 直方图：案卷类型（大类）分布 */
    private List<NameValue> caseTypeDist;

    /** 直方图：案件类别（小类 / 案由）分布，按数量降序 */
    private List<NameValue> categoryDist;

    /** 直方图：到期分布（逾期 / 今天 / 3天内 / 7天内 / 更晚 / 未设期限） */
    private List<NameValue> dueDist;

    /** 直方图：逾期账龄分布 */
    private List<NameValue> overdueAgeDist;

    /** 直方图：未来 N 天每日待办量 */
    private List<TrendPoint> upcoming;

    /** 直方图：部门在手案件负载 */
    private List<NameValue> deptLoad;

    /** 直方图：承办人在手案件负载（含逾期数），默认 Top 10 */
    private List<NameValue> ownerLoad;

    /** 堆叠趋势：近 N 天每天新增按案卷类型拆分（类型分色可视化用） */
    private List<TypeTrendPoint> typeTrend;

    /** 堆叠矩阵：到期桶 × 案卷类型（类型分色的到期分布用） */
    private List<DueBucketByType> dueByType;

    /** 本次统计实际应用的筛选条件（回显用） */
    private List<String> appliedCaseTypes;
    private List<String> appliedDueBuckets;
    private List<String> appliedPriorities;

    @Data
    public static class TrendPoint {
        private String date;
        private long created;
        private long done;
        private long total;

        public TrendPoint() {
        }

        public TrendPoint(String date, long created, long done) {
            this.date = date;
            this.created = created;
            this.done = done;
        }
    }

    /** 每天新增按案卷类型拆分：四个大类各自的当天新增数 */
    @Data
    public static class TypeTrendPoint {
        private String date;
        private long criminal;
        private long administrative;
        private long preliminary;
        private long civil;

        public TypeTrendPoint() {
        }

        public TypeTrendPoint(String date) {
            this.date = date;
        }
    }

    /** 到期桶内按案卷类型拆分的计数矩阵 */
    @Data
    public static class DueBucketByType {
        private String code;
        private String name;
        private long criminal;
        private long administrative;
        private long preliminary;
        private long civil;
        private long noneType;

        public DueBucketByType() {
        }

        public DueBucketByType(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    @Data
    public static class NameValue {
        private String code;
        private String name;
        private long value;
        /** 其中逾期的数量（仅负载类统计使用） */
        private long overdue;

        public NameValue() {
        }

        public NameValue(String code, String name, long value) {
            this.code = code;
            this.name = name;
            this.value = value;
        }
    }
}
