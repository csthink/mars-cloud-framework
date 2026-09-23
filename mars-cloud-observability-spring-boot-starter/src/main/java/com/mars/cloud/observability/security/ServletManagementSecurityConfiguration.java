package com.mars.cloud.observability.security;

import com.mars.cloud.observability.internal.ManagementAccess;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Servlet 栈的管理端点认证链：只匹配 Actuator 端点，health 放行，其余要 Basic 认证。
 *
 * <p>链的优先级最高且只匹配 Actuator 端点，因此部署物自己的业务安全链不受影响，
 * 两条链共存。管理端口与业务端口分开时，Spring Boot 把父上下文的安全过滤器注册进
 * 管理子上下文，本链因此在管理端口上生效，而业务端口上根本没有 Actuator 端点。
 */
public class ServletManagementSecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    SecurityFilterChain marsManagementSecurityFilterChain(HttpSecurity http,
                                                          Environment environment) throws Exception {
        ManagementCredentials credentials = new ManagementCredentials(
                ManagementAccess.username(environment), ManagementAccess.password(environment));
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new InMemoryUserDetailsManager(credentials.user()));
        provider.setPasswordEncoder(credentials.encoder());
        AuthenticationManager manager = new ProviderManager(provider);

        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                // 管理端点由运维工具与采集器访问，它们不带 CSRF 令牌。
                .csrf(csrf -> csrf.disable())
                .authenticationManager(manager)
                .httpBasic(basic -> basic.realmName(ManagementCredentials.REALM))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
