package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 案件盯办看板：三大子模块 + 待审批 四组计数。
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
}
