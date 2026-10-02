package com.caseflow.security;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 权限拦截器：拦住普通民警调用「高级功能」。
 *
 * <p>执行顺序在 {@link LoginInterceptor} 之后，所以到这里 {@link AuthContext} 里已经有登录人。
 *
 * <p>受限标记有两种写法：方法上挂 {@code @FullAccessOnly}，或整个 Controller 类上挂。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 静态资源、欢迎页不是 HandlerMethod，直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod hm = (HandlerMethod) handler;
        FullAccessOnly rule = hm.getMethodAnnotation(FullAccessOnly.class);
        if (rule == null) {
            rule = hm.getBeanType().getAnnotation(FullAccessOnly.class);
        }
        if (rule == null) {
            return true;
        }

        CurrentUser user = AuthContext.get();
        if (user != null && Roles.isFullAccess(user.getRole())) {
            return true;
        }

        String what = rule.value().isEmpty() ? "该操作" : rule.value();
        writeForbidden(response, "当前角色（"
                + (user == null ? "未登录" : Roles.name(user.getRole())) + "）无权执行：" + what
                + "。如需开通请联系所长或法制员。");
        return false;
    }

    private void writeForbidden(HttpServletResponse response, String msg) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"code\":403,\"msg\":\"" + msg + "\",\"data\":null}");
        } catch (Exception ignored) {
        }
    }
}
