package com.caseflow.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 令牌存储（内存版）。后续可平滑替换为 Redis。
 *
 * <p><b>同一账号允许多端在线</b>（电脑 + 手机 + 平板）。这是刻意设计：
 * 内网 / 跨网访问场景下，一个人同时用电脑和手机看案件是常态，
 * 早先的「单点登录」会让手机一登录就把电脑挤掉线，用起来像出了 bug。
 * 代价是同一个账号最多保留 {@link #MAX_SESSIONS_PER_USER} 个会话，
 * 超出时挤掉最早登录的那个，避免泄漏的旧令牌长期有效。
 *
 * <p>停用 / 删除账号时会调用 {@link #removeAll(Long)} 立刻把该账号全部踢下线 ——
 * 光靠 {@code sys_user.status} 状态位是不够的，拦截器只校验令牌、不会每次回查用户状态。
 */
@Slf4j
@Component
public class TokenStore {

    /** 同一账号最多同时保留几个会话（多出来的按登录时间挤掉最早的） */
    private static final int MAX_SESSIONS_PER_USER = 5;

    private final ConcurrentMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public String createSession(CurrentUser user, long expireHours) {
        cleanupExpired();
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new SessionInfo(user, LocalDateTime.now().plusHours(expireHours)));
        evictOldestBeyondLimit(user.getUserId());
        return token;
    }

    public CurrentUser resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        SessionInfo info = sessions.get(token);
        if (info == null) {
            return null;
        }
        if (LocalDateTime.now().isAfter(info.expireAt)) {
            sessions.remove(token);
            return null;
        }
        return info.user;
    }

    /** 退出登录：只销毁当前这一个会话，不动同账号在其它设备上的登录态 */
    public void remove(String token) {
        sessions.remove(token);
    }

    /** 把某个账号全部踢下线（停用 / 删除账号后调用） */
    public int removeAll(Long userId) {
        if (userId == null) {
            return 0;
        }
        List<String> mine = sessions.entrySet().stream()
                .filter(e -> userId.equals(e.getValue().user.getUserId()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        mine.forEach(sessions::remove);
        return mine.size();
    }

    public int onlineCount() {
        cleanupExpired();
        return sessions.size();
    }

    public Map<String, SessionInfo> snapshot() {
        cleanupExpired();
        return sessions;
    }

    /** 账号在线端数（有几个设备登着） */
    public int sessionCountOf(Long userId) {
        cleanupExpired();
        return (int) sessions.values().stream()
                .filter(s -> userId != null && userId.equals(s.user.getUserId()))
                .count();
    }

    private void evictOldestBeyondLimit(Long userId) {
        List<Map.Entry<String, SessionInfo>> mine = new ArrayList<>();
        for (Map.Entry<String, SessionInfo> e : sessions.entrySet()) {
            if (userId.equals(e.getValue().user.getUserId())) {
                mine.add(e);
            }
        }
        int over = mine.size() - MAX_SESSIONS_PER_USER;
        if (over <= 0) {
            return;
        }
        mine.sort(Comparator.comparing(e -> e.getValue().createdAt));
        for (int i = 0; i < over; i++) {
            sessions.remove(mine.get(i).getKey());
            log.info("账号 {} 的会话数超过 {}，已挤掉最早的一个会话", userId, MAX_SESSIONS_PER_USER);
        }
    }

    /** 清理无人再访问的过期会话，避免长期运行时内存只增不减。 */
    private void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        sessions.entrySet().removeIf(e -> now.isAfter(e.getValue().expireAt));
    }

    private static class SessionInfo {
        private final CurrentUser user;
        private final LocalDateTime expireAt;
        private final LocalDateTime createdAt = LocalDateTime.now();

        SessionInfo(CurrentUser user, LocalDateTime expireAt) {
            this.user = user;
            this.expireAt = expireAt;
        }
    }
}
