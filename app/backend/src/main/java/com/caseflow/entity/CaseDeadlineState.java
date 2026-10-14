package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("case_deadline_state")
public class CaseDeadlineState {
    @TableId(type = IdType.INPUT)
    private Long caseId;
    private String detentionTerm;
    private LocalDateTime pausedAt;
    private LocalDateTime pausedDeadline;
    private Long pausedSeconds;
    private LocalDateTime pretrialReceivedAt;
    private LocalDateTime legalReceivedAt;
    private LocalDateTime pretrialSentAt;
    private LocalDateTime legalSentAt;
    private LocalDateTime submittedAt;
    private LocalDateTime lastSummonsAt;
    @TableField("admin_30_doc_at")
    private LocalDateTime admin30DocAt;
    private LocalDateTime adminExtendedAt;
    @TableField("admin_60_doc_at")
    private LocalDateTime admin60DocAt;
    private LocalDateTime adminExtended90At;
    private LocalDateTime filingAt;
    private LocalDateTime arrestTargetAt;
    @TableField("admin_check_3_at")
    private LocalDateTime adminCheck3At;
    @TableField("admin_check_10_at")
    private LocalDateTime adminCheck10At;
    @TableField("admin_check_20_at")
    private LocalDateTime adminCheck20At;
    private LocalDateTime updatedAt;
}
