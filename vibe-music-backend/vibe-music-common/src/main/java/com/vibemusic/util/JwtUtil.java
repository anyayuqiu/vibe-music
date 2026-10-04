package com.vibemusic.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 */
public class JwtUtil {

    private static volatile Algorithm algorithm;

    public static void configureSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("请在 application-dev.yml 中设置至少 32 字符的 vibe-music.jwt.secret");
        }
        algorithm = Algorithm.HMAC256(secret);
    }

    private static Algorithm signingAlgorithm() {
        if (algorithm == null) {
            throw new IllegalStateException("JWT 密钥尚未配置");
        }
        return algorithm;
    }
    // 设置 JWT 的过期时间 6 小时
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 6;

    /**
     * 生成 JWT token
     *
     * @param claims 自定义的业务数据
     * @return JWT token
     */
    public static String generateToken(Map<String, Object> claims) {
        return JWT.create()
                .withClaim("claims", claims) // 自定义的业务数据
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // 设置过期时间
                .sign(signingAlgorithm());
    }

    /**
     * 解析 JWT token
     *
     * @param token JWT token
     * @return 自定义的业务数据
     */
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(signingAlgorithm())
                .build()
                .verify(token)
                .getClaim("claims")
                .asMap();
    }

}
