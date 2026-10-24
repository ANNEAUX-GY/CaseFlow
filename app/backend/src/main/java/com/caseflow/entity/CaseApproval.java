package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批留痕（案件盯办模块）：侦查终结审批 / 强制措施变更确认。
 */
@Data
@TableName("case_approval")
public class CaseApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    /** INVESTIGATION_DONE 侦查终结审批 / MEASURE 强制措施变更 */
    private String approveType;
    /** PENDING 待审批 / APPROVED 同意 / RETURNED 退回补侦（MEASURE 退回） */
    private String result;
    /** 审批意见 */
    private String comment;
    private Long approverId;
    private String approverName;
    private LocalDateTime createdAt;

    // ---- 强制措施变更申请（2026-10-24）：承办人提交的申请内容必须留底，
    //      否则审批人只看得到"要改措施"，不知道改成哪种、从哪天起、期限到哪天。----
    /** 申请的目标措施（NONE 表示结束当前措施） */
    private String applyMeasure;
    /** 申请的措施起算日 */
    private LocalDateTime applyDate;
    /** 申请的期限届满日 */
    private LocalDateTime applyDeadline;
    /** 申请时的措施（便于审批人对比 原→新） */
    private String fromMeasure;
    private Long applicantId;
    private String applicantName;
    private LocalDateTime appliedAt;
}
