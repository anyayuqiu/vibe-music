package com.vibemusic.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;

public final class PasswordUtil {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private PasswordUtil() {}

    public static String encode(String password) {
        return ENCODER.encode(password);
    }

    public static boolean matches(String password, String stored) {
        if (stored == null) return false;
        if (stored.startsWith("$2")) return ENCODER.matches(password, stored);
        return DigestUtils.md5DigestAsHex(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(stored);
    }

    public static boolean needsUpgrade(String stored) {
        return stored != null && !stored.startsWith("$2");
    }
}
