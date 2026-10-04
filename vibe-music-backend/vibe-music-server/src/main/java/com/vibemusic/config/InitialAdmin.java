package com.vibemusic.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vibemusic.mapper.AdminMapper;
import com.vibemusic.model.entity.Admin;
import com.vibemusic.util.PasswordUtil;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InitialAdmin {
    @Bean
    CommandLineRunner createInitialAdmin(AdminMapper mapper) {
        return args -> {
            String username = System.getenv("VIBE_INITIAL_ADMIN_USER");
            String password = System.getenv("VIBE_INITIAL_ADMIN_PASSWORD");
            if (username == null || password == null) return;
            if (mapper.selectCount(new QueryWrapper<>()) != 0) return;
            if (!username.matches("[a-zA-Z0-9_-]{4,16}") || password.length() < 12)
                throw new IllegalStateException("初始管理员用户名或密码不符合要求");
            mapper.insert(new Admin().setUsername(username).setPassword(PasswordUtil.encode(password)));
        };
    }
}
