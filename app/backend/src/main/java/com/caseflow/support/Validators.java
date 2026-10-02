package com.caseflow.support;

import java.util.regex.Pattern;

/**
 * 通用格式校验。
 */
public final class Validators {

    /** 中国大陆手机号：1 开头，第二位 3-9，共 11 位 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    private Validators() {
    }

    public static boolean isPhone(String s) {
        return s != null && PHONE.matcher(s.trim()).matches();
    }

    /** 手机号脱敏：138****8000，用于日志与列表展示 */
    public static String maskPhone(String s) {
        if (s == null || s.length() != 11) {
            return s;
        }
        return s.substring(0, 3) + "****" + s.substring(7);
    }

    public static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
