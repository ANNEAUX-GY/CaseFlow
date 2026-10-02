package com.caseflow.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「只有全权限角色（所长 / 副所长 / 法制员 / 系统管理员）才能调用」的接口。
 *
 * <p>由 {@link PermissionInterceptor} 统一拦截校验，业务代码里不需要再写 if 判断。
 * 挂在方法上表示该方法受限，挂在类上表示整个 Controller 受限。
 *
 * <p>权限边界集中在这里和 {@link Roles#isFullAccess(String)}，不要散落到各处。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FullAccessOnly {

    /** 受限原因，用于 403 提示，例如「指派案件」 */
    String value() default "";
}
