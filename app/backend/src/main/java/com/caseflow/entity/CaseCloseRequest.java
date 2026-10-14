package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("case_close_request")
public class CaseCloseRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String outcome;
    private String note;
    private String operationTime;
    private String operationPlatform;
    private String operationAction;
    private String status;
    private Long submittedBy;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;
}
