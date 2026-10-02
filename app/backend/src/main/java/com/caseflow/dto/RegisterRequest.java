package com.caseflow.dto;

import lombok.Data;

/**
 * 自助注册请求。
 */
@Data
public class RegisterRequest {

    /** 登录名，可留空 —— 留空时自动用手机号作为登录名 */
    private String username;

    /** 明文密码，入库前会做 BCrypt 哈希 */
    private String password;

    /** 真实姓名 */
    private String displayName;

    /** 手机号，必填且唯一；也可用于登录 */
    private String phone;

    private String dept;

    /** 申请角色：STAFF / LAW_OFFICER / DEPUTY_CHIEF / CHIEF */
    private String applyRole;

    /**
     * 要绑定的员工档案 ID（二选一）。
     *
     * <p>新注册的账号<b>必须</b>关联一名员工：组织树里已有本人档案就选 {@code employeeId}，
     * 没有就填 {@link #newEmployee} 现场建一个。两者都空会被后端拒绝。
     */
    private Long employeeId;

    /** 现场新建的员工档案（没有可选档案时的兜底），与 {@link #employeeId} 二选一 */
    private EmployeeBrief newEmployee;
}
