package com.vibemusic.config;

import com.vibemusic.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    public JwtConfig(@Value("${vibe-music.jwt.secret}") String secret) {
        JwtUtil.configureSecret(secret);
    }
}
