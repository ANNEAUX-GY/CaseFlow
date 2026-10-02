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
    /** APPROVED 同意 / RETURNED 退回补侦 */
    private String result;
    /** 审批意见 */
    private String comment;
    private Long approverId;
    private String approverName;
    private LocalDateTime createdAt;
}
