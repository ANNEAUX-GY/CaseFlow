package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 待确认的结案申请（2026-11-13）：工作台「待手动确认」面板用。
 *
 * <p>管理用户要看的是「哪个案子、结什么结果、谁申请的」，
 * 光给一条 case_approval 只有 caseId，界面还得再查一次案件，索性在这里拼好。
 */
@Data
public class CloseApprovalVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long caseId;
    private String caseNo;
    private String caseName;
    /** 结案结果编码（WITHDRAW/TRANSFER_PRETRIAL/TRANSFER_OTHER/MEDIATION/PENALTY/...） */
    private String outcome;
    /** 结案结果中文名 */
    private String outcomeName;
    /** 结案前的案件状态（退回/撤回时据此回退） */
    private String fromStatus;
    /** 申请人填的说明 */
    private String comment;
    private String applicantName;
    private LocalDateTime appliedAt;
}
