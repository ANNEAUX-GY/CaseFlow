package com.caseflow.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色定义与权限分档。
 *
 * <p>全权限（可指派案件、维护员工图谱、管理账号、撤回操作）：
 * 所长 CHIEF、副所长 DEPUTY_CHIEF、法制员 LAW_OFFICER，以及内置的系统管理员 BOSS。
 *
 * <p>基础权限（只能看案件、维护自己承办的案件进度）：
 * 普通民警 STAFF。
 *
 * <p>以后要调整权限边界，只改 {@link #isFullAccess(String)} 一处即可，
 * 接口上的 {@code @FullAccessOnly} 注解不用动。
 */
public final class Roles {

    public static final String BOSS = "BOSS";
    public static final String CHIEF = "CHIEF";
    public static final String DEPUTY_CHIEF = "DEPUTY_CHIEF";
    public static final String LAW_OFFICER = "LAW_OFFICER";
    public static final String STAFF = "STAFF";

    /** 角色 code -> 中文名 */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    /** 可注册（可申请）的角色 —— BOSS 不允许自行注册 */
    private static final Map<String, String> APPLICABLE = new LinkedHashMap<>();

    static {
        NAMES.put(CHIEF, "所长");
        NAMES.put(DEPUTY_CHIEF, "副所长");
        NAMES.put(LAW_OFFICER, "法制员");
        NAMES.put(STAFF, "普通民警");
        NAMES.put(BOSS, "系统管理员");

        APPLICABLE.put(STAFF, "普通民警");
        APPLICABLE.put(LAW_OFFICER, "法制员");
        APPLICABLE.put(DEPUTY_CHIEF, "副所长");
        APPLICABLE.put(CHIEF, "所长");
    }

    private Roles() {
    }

    /** 是否拥有全部权限（指派、员工维护、账号管理、撤回） */
    public static boolean isFullAccess(String role) {
        return BOSS.equals(role)
                || CHIEF.equals(role)
                || DEPUTY_CHIEF.equals(role)
                || LAW_OFFICER.equals(role);
    }

    /** 角色是否合法 */
    public static boolean isValid(String role) {
        return role != null && NAMES.containsKey(role);
    }

    /** 是否允许自行注册该角色 */
    public static boolean isApplicable(String role) {
        return role != null && APPLICABLE.containsKey(role);
    }

    public static String name(String role) {
        String n = NAMES.get(role);
        return n == null ? role : n;
    }

    public static Map<String, String> all() {
        return NAMES;
    }

    public static Map<String, String> applicable() {
        return APPLICABLE;
    }
}
