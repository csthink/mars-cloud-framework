package com.mars.cloud.observability.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;

/**
 * 管理端点的单一账号。密码在启动时用 bcrypt 编码后只留在这条链里，
 * 不注册全局 UserDetailsService，因此不会影响部署物自己的认证方式，
 * 也不会让 Spring Boot 生成默认用户。
 */
public final class ManagementCredentials {

    /** 认证提示里的领域名，浏览器据此区分弹窗来源。 */
    public static final String REALM = "mars-management";

    /** bcrypt 只处理口令的前 72 个字节，Spring Security 对更长的口令直接拒绝编码。 */
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserDetails user;
    private final PasswordEncoder encoder;

    /** 账号与口令取自环境里解析后的值，与认证链的装配条件读同一个来源。 */
    public ManagementCredentials(String username, String password) {
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        if (length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new IllegalStateException("mars.observability.management.password 不能超过 "
                    + BCRYPT_MAX_PASSWORD_BYTES + " 字节（按 UTF-8 计），实际 " + length + " 字节：口令用 bcrypt 编码");
        }
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
