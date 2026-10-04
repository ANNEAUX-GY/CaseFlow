package com.caseflow.support;

import com.caseflow.entity.OperationLog;
import com.caseflow.mapper.OperationLogMapper;
import com.caseflow.security.AuthContext;
import com.caseflow.service.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志：所有关键动作统一埋点，既是「案件追溯」的数据底座，
 * 也是「撤回上一步」的依据（写操作会额外带上对象的前后快照）。
 *
 * <p>每条日志落库后同时向全站 SSE 连接广播事件——这是「办理进度实时刷新」
 * 的唯一事件源：埋点在这里，任何入口的写操作都逃不过。
 */
@Service
public class LogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private OperationLogMapper logMapper;
    @Resource
    private SseHub sseHub;
    /** 统一信箱：日志落库后派生通知（NotificationService 只依赖 mapper/sseHub，无循环） */
    @Resource
    private com.caseflow.service.NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 普通日志：不带快照，因而不支持撤回（登录、导入等） */
    public OperationLog log(String module, String action, String targetType, Long targetId, String content) {
        return log(module, action, targetType, targetId, content, null, null, null);
    }

    /** 带快照的日志：before/after 为对象完整状态的 JSON，撤回时回写 before */
    public OperationLog log(String module, String action, String targetType, Long targetId,
                           String content, String snapshotBefore, String snapshotAfter) {
        return log(module, action, targetType, targetId, content, snapshotBefore, snapshotAfter, null);
    }

    public OperationLog log(String module, String action, String targetType, Long targetId, String content,
                           String snapshotBefore, String snapshotAfter, Long undoOf) {
        OperationLog log = new OperationLog();
        log.setModule(module);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setContent(content);
        log.setOperatorId(AuthContext.userId());
        log.setOperatorName(AuthContext.userName());
        log.setSnapshotBefore(snapshotBefore);
        log.setSnapshotAfter(snapshotAfter);
        log.setUndone(0);
        log.setUndoOf(undoOf);
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
        broadcast(log);
        // 派生信箱通知（唯一埋点：全站写操作在此进信箱）。失败绝不影响主流程。
        try {
            notificationService.onLog(log);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(LogService.class)
                    .warn("[LogService] 信箱通知派生失败（不影响业务）：{}", e.getMessage());
        }
        return log;
    }

    /** 落库成功后向全站在线连接广播；广播失败只记日志，绝不影响主流程 */
    private void broadcast(OperationLog log) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("module", log.getModule());
            event.put("action", log.getAction());
            event.put("actionName", OperationLogService.actionName(log.getModule(), log.getAction()));
            event.put("targetType", log.getTargetType());
            event.put("targetId", log.getTargetId());
            event.put("content", log.getContent());
            event.put("operatorName", log.getOperatorName());
            event.put("createdAt", log.getCreatedAt() == null ? null : log.getCreatedAt().format(TS));
            sseHub.broadcast(event);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(LogService.class)
                    .warn("[LogService] 事件广播失败（不影响业务）：{}", e.getMessage());
        }
    }

    /** 标记某条日志已被撤回，并记录撤回它所产生的日志 ID */
    public void markUndone(Long logId, Long undoLogId) {
        OperationLog patch = new OperationLog();
        patch.setId(logId);
        patch.setUndone(1);
        patch.setUndoLogId(undoLogId);
        logMapper.updateById(patch);
    }
}
