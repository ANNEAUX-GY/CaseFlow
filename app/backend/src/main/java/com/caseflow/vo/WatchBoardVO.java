package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 案件盯办看板：三大子模块 + 待审批 四组计数，以及看板图表数据。
 *
 * <p>四组计数的口径是「在办 + 已采取措施状态」：初查 / 刑拘在办 / 取保及监居三者
 * <b>互斥且完备</b>（强制措施只能取其一），在办总数 = 三者之和；而「待审批」是横切维度
 * （按侦查进度筛），会与前三者重叠，所以环形图只画前三者，不把待审批当第四块。
 */
@Data
public class WatchBoardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 初查案件：在手数 / 有逾期计划的 / 有嫌疑人的 */
    private long initialTotal;
    private long initialPlanOverdue;
    private long initialWithSuspect;

    /** 刑拘在办：在手数 / 措施临期(≤7天) / 措施超期 */
    private long detentionTotal;
    private long detentionDueSoon;
    private long detentionOverdue;

    /** 取保及监居：在手数 / 30天内到期 */
    private long bailTotal;
    private long bailDueSoon;

    /** 待审批：总数 / 滞留≥2天 */
    private long approvalTotal;
    private long approvalStale;

    // ---------------- 图表数据（2026-10-09） ----------------
    // 复用 StatsVO.NameValue（code/name/value），与工作台、案件管理的图表结构一致，
    // 前端可以直接套同一套 option 构造函数，不用为盯办再写一份。

    /**
     * 该大类下各小类的在办案件数，条数降序、「未分类」垫底。
     *
     * <p><b>刻意按大类统计、不受当前小类筛选影响</b>：选中「电诈」后这张图仍要显示
     * 全大类的分布，否则只剩一根柱子，既没法横向比较、也点不回去换小类。
     */
    private List<StatsVO.NameValue> categoryDist;

    /**
     * 已采取措施（刑拘 / 取保 / 监居）的在办案件按期限余量分桶：
     * 已超期 / 7天内到期 / 8-30天 / 30天以上 / 未登记期限。
     *
     * <p>空桶一并返回（值为 0）——柱状图少一根柱子会让人以为该情况不存在；
     * 「未登记期限」也单独成桶，否则「刑拘 3 件但图上只有 1 件」看起来像数据丢了。
     */
    private List<StatsVO.NameValue> dueDist;
}
