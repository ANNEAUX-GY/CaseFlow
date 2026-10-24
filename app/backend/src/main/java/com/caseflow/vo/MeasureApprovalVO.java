package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待审批的强制措施变更（2026-10-24）：工作台审批面板用。
 *
 * <p>审批人要看的是"哪个案子、从什么措施改到什么措施、什么时候起、期限到哪天、谁申请的"，
 * 光给一条 case_approval 只有 caseId，界面还得再查一次案件，索性在这里拼好。
 */
@Data
public class MeasureApprovalVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    private String caseNo;
    private String caseName;
    /** 申请前的措施 */
    private String fromMeasure;
    private String fromMeasureName;
    /** 申请的目标措施（NONE = 结束当前措施） */
    private String applyMeasure;
    private String applyMeasureName;
    private LocalDateTime applyDate;
    private LocalDateTime applyDeadline;
    /** 申请人填的说明 */
    private String comment;
    private String applicantName;
    private LocalDateTime appliedAt;
}
