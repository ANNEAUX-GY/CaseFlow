package com.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 指派关系。改派时旧记录置为 REPLACED 而非物理删除，为后续追溯留痕。
 */
@Data
@TableName("case_assignee")
public class CaseAssignee implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private Long employeeId;
    private String assignRole;
    private String note;
    private String status;
    private Long assignedBy;
    private LocalDateTime assignedAt;
    private LocalDateTime closedAt;
}
