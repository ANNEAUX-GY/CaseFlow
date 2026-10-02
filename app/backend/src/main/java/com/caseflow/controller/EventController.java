package com.caseflow.controller;

import com.caseflow.security.CurrentUser;
import com.caseflow.security.TokenStore;
import com.caseflow.support.SseHub;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * 全局事件流（SSE）：浏览器 EventSource 长连接，实时接收案件操作事件。
 *
 * <p>EventSource 无法携带自定义请求头，token 通过 ?token= 传入
 * （LoginInterceptor 已做「header 优先、query 兜底」的兼容）。
 */
@RestController
@RequestMapping("/events")
public class EventController {

    @Resource
    private TokenStore tokenStore;
    @Resource
    private SseHub sseHub;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String token) {
        CurrentUser user = tokenStore.resolve(token);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(sseHub.register());
    }
}
