package com.caseflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 案件指派系统（CaseFlow）启动类。
 */
@MapperScan("com.caseflow.mapper")
@SpringBootApplication
public class CaseFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaseFlowApplication.class, args);
    }
}
