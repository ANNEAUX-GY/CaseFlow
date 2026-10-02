package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 账号列表项（账号管理页用）。
 */
@Data
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String displayName;
    private String role;
    private String roleName;
    private Long employeeId;
    /** 关联的员工图谱姓名 */
    private String employeeName;
    /** 关联员工的部门，展示时补齐信息用 */
    private String employeeDept;
    /** 关联员工档案的来源：IMPORT / MANUAL / SELF_REGISTER（注册者自建，审核时要重点核对） */
    private String employeeOrigin;
    private String phone;
    private String dept;
    private String applyRole;
    private String applyRoleName;
    /** 0 待审核 / 1 已通过 / 2 已驳回 */
    private Integer auditStatus;
    private String auditRemark;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime auditedAt;
    /** 是否拥有全部权限 */
    private Boolean fullAccess;
}
