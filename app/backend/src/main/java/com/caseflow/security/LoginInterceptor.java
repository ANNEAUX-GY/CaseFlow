package com.caseflow.security;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 简易鉴权拦截器：除 /api/auth/** 与静态资源外需携带 X-Token。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Token";

    @Resource
    private TokenStore tokenStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 静态资源（index.html、/assets/**）和欢迎页不是 HandlerMethod，必须放行。
        // 否则前端页面本身会被当成未登录请求返回 401 JSON，浏览器打开就是白屏——
        // 这个坑在把前端打包进 jar 一起部署时一定会遇到。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // EventSource（SSE）无法携带自定义请求头，token 允许通过 ?token= 传入（header 优先）
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isEmpty()) {
            token = request.getParameter("token");
        }
        CurrentUser user = tokenStore.resolve(token);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已过期\",\"data\":null}");
            } catch (Exception ignored) {
            }
            return false;
        }
        AuthContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
