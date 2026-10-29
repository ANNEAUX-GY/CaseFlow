package com.caseflow.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色定义与权限分档。
 *
 * <p>全权限（可指派案件、维护员工图谱、管理账号、撤回操作）：
 * 所长 CHIEF、副所长 DEPUTY_CHIEF、队长 CAPTAIN（权限同副所长）、
 * 法制员 LAW_OFFICER，以及内置的系统管理员 BOSS。
 *
 * <p>研判员 JUDGE（2026-10-29）：查看全量案件 + 录入嫌疑人身份信息，
 * 不参与指派、账号管理、阶段流转等管理动作。
 *
 * <p>基础权限（只能看案件、维护自己承办的案件进度）：
 * 普通民警 STAFF。
 *
 * <p>以后要调整权限边界，只改 {@link #isFullAccess(String)} / {@link #canViewAllCases(String)}
 * 两处即可，接口上的 {@code @FullAccessOnly} 注解不用动。
 */
public final class Roles {

    public static final String BOSS = "BOSS";
    public static final String CHIEF = "CHIEF";
    public static final String DEPUTY_CHIEF = "DEPUTY_CHIEF";
    public static final String LAW_OFFICER = "LAW_OFFICER";
    public static final String STAFF = "STAFF";
    /** 队长：权限与副所长一致（2026-10-29） */
    public static final String CAPTAIN = "CAPTAIN";
    /** 研判员：查看全量案件 + 录入嫌疑人身份信息（2026-10-29） */
    public static final String JUDGE = "JUDGE";

    /** 角色 code -> 中文名 */
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    /** 可注册（可申请）的角色 —— BOSS 不允许自行注册 */
    private static final Map<String, String> APPLICABLE = new LinkedHashMap<>();

    static {
        NAMES.put(CHIEF, "所长");
        NAMES.put(DEPUTY_CHIEF, "副所长");
        NAMES.put(CAPTAIN, "队长");
        NAMES.put(LAW_OFFICER, "法制员");
        NAMES.put(JUDGE, "研判员");
        NAMES.put(STAFF, "普通民警");
        NAMES.put(BOSS, "系统管理员");

        APPLICABLE.put(STAFF, "普通民警");
        APPLICABLE.put(JUDGE, "研判员");
        APPLICABLE.put(LAW_OFFICER, "法制员");
        APPLICABLE.put(CAPTAIN, "队长");
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
                || CAPTAIN.equals(role)
                || LAW_OFFICER.equals(role);
    }

    /**
     * 是否可查看全量案件（不受「本人名下」范围收敛）。
     *
     * <p>2026-10-29 新增研判员：研判员需要看全量案件以录入嫌疑人身份信息，
     * 但不应拥有指派、账号管理等管理权限，所以单独开这一档。
     * 全权限角色当然也能看全量。
     */
    public static boolean canViewAllCases(String role) {
        return isFullAccess(role) || JUDGE.equals(role);
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
