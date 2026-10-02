package com.caseflow.security;

/**
 * 登录态持有者。演示版为内存 Map；
 * 生产建议替换为 Redis + JWT（见 docs 路线图）。
 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getUserId();
    }

    public static String userName() {
        CurrentUser u = HOLDER.get();
        return u == null ? "系统" : u.getDisplayName();
    }

    public static String role() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getRole();
    }

    /** 当前登录人是否拥有全权限（所长 / 副所长 / 法制员 / 系统管理员） */
    public static boolean isFullAccess() {
        CurrentUser u = HOLDER.get();
        return u != null && Roles.isFullAccess(u.getRole());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
