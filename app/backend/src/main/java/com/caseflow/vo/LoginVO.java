package com.caseflow.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录结果。
 */
@Data
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String token;
    private Long userId;
    private String username;
    private String displayName;
    private String role;
    /** 角色中文名，前端直接显示，避免两边各维护一份映射 */
    private String roleName;
    private Long employeeId;
    /** 是否拥有全部权限（指派 / 员工维护 / 账号管理 / 撤回） */
    private Boolean fullAccess;
}
