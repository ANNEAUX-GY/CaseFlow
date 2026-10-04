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

    /** 办案组别 INITIAL初查组/CLEAR清案组/NONE不限（指派约束用） */
    private String policeGroup;
    private String policeGroupName;
    private String pathName;
    private String assignRole;
    private String note;
    private String status;
    private LocalDateTime assignedAt;
}
