package com.caseflow.controller;

import com.caseflow.common.Result;
import com.caseflow.service.SystemConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统级配置（2026-11-13）：全局开关读写。
 */
@RestController
@RequestMapping("/system-config")
public class SystemConfigController {

    @Resource
    private SystemConfigService configService;

    /** 当前案件阶段变更模式（登录即可读，前端据此渲染开关状态） */
    @GetMapping("/flow-change-mode")
    public Result<Map<String, Object>> flowChangeMode() {
        String mode = configService.flowChangeMode();
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode);
        m.put("modeLabel", configService.modeLabel(mode));
        return Result.ok(m);
    }

    /** 设置案件阶段变更模式（管理层专属，service 内做权限校验） */
    @PostMapping("/flow-change-mode")
    public Result<Map<String, Object>> setFlowChangeMode(@RequestBody Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        configService.setFlowChangeMode(mode);
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode);
        m.put("modeLabel", configService.modeLabel(mode));
        return Result.ok(m);
    }

    /** 当前结案模式（登录即可读，前端据此渲染开关状态） */
    @GetMapping("/close-mode")
    public Result<Map<String, Object>> closeMode() {
        String mode = configService.closeMode();
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode);
        m.put("modeLabel", configService.modeLabel(mode));
        return Result.ok(m);
    }

    /** 设置结案模式（管理层专属，service 内做权限校验） */
    @PostMapping("/close-mode")
    public Result<Map<String, Object>> setCloseMode(@RequestBody Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        configService.setCloseMode(mode);
        Map<String, Object> m = new HashMap<>();
        m.put("mode", mode);
        m.put("modeLabel", configService.modeLabel(mode));
        return Result.ok(m);
    }
}
