package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 承办人视图。
 */
@Data
public class AssigneeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long employeeId;
    private String employeeName;
    private String dept;
    private String title;
    private String pathName;
    private String assignRole;
    private String note;
    private String status;
    private LocalDateTime assignedAt;
}
