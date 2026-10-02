package com.caseflow.security;

import lombok.Data;

/**
 * 当前登录人上下文（ThreadLocal）。
 */
@Data
public class CurrentUser {

    private Long userId;
    private String username;
    private String displayName;
    private String role;
    private Long employeeId;

    public CurrentUser() {
    }

    public CurrentUser(Long userId, String username, String displayName, String role, Long employeeId) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.employeeId = employeeId;
    }
}
