package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待审批的案件状态变更（2026-10-30）：工作台审批面板用。
 *
 * <p>审批人要看的是"哪个案子、从什么阶段流转到什么阶段、谁申请的"，
 * 光给一条 case_approval 只有 caseId，界面还得再查一次案件，索性在这里拼好。
 */
@Data
public class FlowApprovalVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    private String caseNo;
    private String caseName;
    /** 申请时的阶段 */
    private String fromStage;
    private String fromStageName;
    /** 流转动作（DETAIN/BAIL/ARREST/RELEASE/CLOSE） */
    private String flowAction;
    private String flowActionName;
    /** 目标阶段 */
    private String targetStage;
    private String targetStageName;
    /** 申请人填的说明 */
    private String comment;
    private String applicantName;
    private LocalDateTime appliedAt;
}
