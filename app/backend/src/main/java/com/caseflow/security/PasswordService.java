package com.caseflow.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希。统一用 BCrypt，数据库里只存哈希值。
 *
 * <p>兼容历史数据：早期版本密码是明文存的（如 boss/admin123）。校验时如果发现库里存的
 * 不是 BCrypt 格式，就按明文比对，**比对成功后立刻把该账号的密码升级成哈希**。
 * 这样老账号不用重置密码也能平滑切到加密存储。
 */
@Component
public class PasswordService {

    /** BCrypt 哈希固定以 $2a$ / $2b$ / $2y$ 开头，可用它区分明文与哈希 */
    private static final String BCRYPT_PREFIX = "$2";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String encode(String raw) {
        return encoder.encode(raw);
    }

    /** 库里存的是否已是哈希 */
    public boolean isHashed(String stored) {
        return stored != null && stored.startsWith(BCRYPT_PREFIX);
    }

    /** 明文与存储值是否匹配（自动兼容历史明文密码） */
    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isEmpty()) {
            return false;
        }
        if (isHashed(stored)) {
            return encoder.matches(raw, stored);
        }
        return stored.equals(raw);
    }
}
