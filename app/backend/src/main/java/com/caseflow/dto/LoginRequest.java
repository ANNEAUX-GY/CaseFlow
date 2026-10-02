package com.caseflow.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 登录参数。
 */
@Data
public class LoginRequest {

    /** 账号：用户名或注册手机号，两者都可登录 */
    @NotBlank(message = "请输入用户名或手机号")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;
}
