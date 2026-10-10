package com.caseflow.config;

import com.caseflow.security.LoginInterceptor;
import com.caseflow.security.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Web 配置：CORS、鉴权拦截、权限拦截、静态资源。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;

    @Resource
    private PermissionInterceptor permissionInterceptor;

    @Value("${caseflow.upload-dir:./data/uploads}")
    private String uploadDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序要紧：先登录校验（401），再权限校验（403）
        //
        // 放行的只有「登录前必须能访问」的那几个：登录、注册及其选项、角色字典、退出。
        // 早先这里图省事写成 excludePathPatterns("/auth/**")，把整个 /auth 都放行了，
        // 结果 GET /auth/info 里 AuthContext 永远是 null，**它一直返回 401「未登录」**
        // （看着像能用、其实谁调谁失败）。前端要拿它做"启动时验令牌真伪"，必须让它真的受鉴权。
        // 以后再往 /auth 下加接口，想清楚是不是登录前就要用：不是的话就别往放行名单里塞。
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/auth/register", "/auth/register/**",
                        "/auth/roles", "/auth/logout",
                        "/h2-console/**", "/console/**", "/error")
                .order(1);
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/**")
                .order(2);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/h2-console/**")
                .addResourceLocations("classpath:/META-INF/resources/");
    }
}
