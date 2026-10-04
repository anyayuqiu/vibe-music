package com.vibemusic.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NotNull CorsRegistry registry) {
                String[] allowedOrigins = Arrays.stream(
                                System.getenv().getOrDefault(
                                        "VIBE_ALLOWED_ORIGIN",
                                        "http://localhost:8090,http://localhost:8089"
                                ).split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new);
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") // 加上 OPTIONS
                        .allowedHeaders("*") // 允许所有请求头
                        .exposedHeaders("Authorization") // 允许前端获取 Authorization 头
                        .allowCredentials(false);
            }
        };
    }
}
