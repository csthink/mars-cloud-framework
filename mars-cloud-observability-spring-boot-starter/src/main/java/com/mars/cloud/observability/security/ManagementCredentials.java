package com.mars.cloud.observability.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理端点的单一账号。密码在启动时用 bcrypt 编码后只留在这条链里，
 * 不注册全局 UserDetailsService，因此不会影响部署物自己的认证方式，
 * 也不会让 Spring Boot 生成默认用户。
 */
public final class ManagementCredentials {

    /** 认证提示里的领域名，浏览器据此区分弹窗来源。 */
    public static final String REALM = "mars-management";

    private final UserDetails user;
    private final PasswordEncoder encoder;

    /** 账号与口令取自环境里解析后的值，与认证链的装配条件读同一个来源。 */
    public ManagementCredentials(String username, String password) {
        this.encoder = new BCryptPasswordEncoder();
        this.user = User.withUsername(username)
                .password(encoder.encode(password))
                .authorities("MANAGEMENT")
                .build();
    }

    public UserDetails user() {
        return user;
    }

    public PasswordEncoder encoder() {
        return encoder;
    }
}
