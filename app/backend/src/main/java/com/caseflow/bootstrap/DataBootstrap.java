package com.caseflow.bootstrap;

import com.caseflow.service.AuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 启动自检：保证至少存在一个可登录账号。
 */
@Component
public class DataBootstrap implements ApplicationRunner {

    @Resource
    private AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        authService.ensureAdmin();
    }
}
