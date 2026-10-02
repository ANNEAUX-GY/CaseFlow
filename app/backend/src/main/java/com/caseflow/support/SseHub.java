package com.caseflow.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件枢纽：所有在线浏览器保持一条长连接，案件发生任何写操作时全站广播。
 *
 * <p>事件由 {@link LogService#log} 统一发布（全量埋点的唯一汇聚点），
 * 前端订阅后用于「办理进度实时刷新」「工作台最近操作自动更新」等全局联动。
 */
@Slf4j
@Component
public class SseHub {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 注册一条在线连接；0L 表示服务端不主动超时，断线由前端 EventSource 自动重连 */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** 广播事件给全部在线连接；单条发送失败直接摘除该连接（多半是客户端已断开） */
    public void broadcast(Map<String, Object> event) {
        if (emitters.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[SseHub] 事件序列化失败：{}", e.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("case-event").data(json, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
