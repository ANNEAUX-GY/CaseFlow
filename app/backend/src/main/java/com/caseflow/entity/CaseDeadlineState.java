package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
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
    private LocalDateTime admin30DocAt;
    private LocalDateTime adminExtendedAt;
    private LocalDateTime admin60DocAt;
    private LocalDateTime adminCheck3At;
    private LocalDateTime adminCheck10At;
    private LocalDateTime adminCheck20At;
    private LocalDateTime updatedAt;
}
