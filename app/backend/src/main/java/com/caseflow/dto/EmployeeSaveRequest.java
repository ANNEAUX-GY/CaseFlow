package com.caseflow.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 员工新增 / 编辑参数。
 */
@Data
public class EmployeeSaveRequest {

    private Long id;

    @NotBlank(message = "姓名不能为空")
    private String name;

    private String employeeNo;
    private Long parentId;
    private String dept;
    private String title;
    /** 办案组别：INITIAL初查组 / CLEAR清案组 / NONE不限（留空视为不限，兼容存量） */
    private String policeGroup;
    private String phone;
    private String email;
    private Integer sortNo;
    private Integer status = 1;
}
