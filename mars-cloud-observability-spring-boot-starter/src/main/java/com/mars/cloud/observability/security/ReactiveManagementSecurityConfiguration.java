package com.mars.cloud.observability.security;

import com.mars.cloud.observability.internal.ManagementAccess;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.reactive.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 响应式栈的管理端点认证链，语义与 Servlet 栈那条相同。
 *
 * <p>classpath 上没有 Spring Security 的响应式部署物（例如网关）不装配本配置，
 * 暴露面由核验器收窄为 health 与 info。
 */
public class ReactiveManagementSecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    SecurityWebFilterChain marsManagementSecurityWebFilterChain(ServerHttpSecurity http,
                                                                Environment environment) {
        ManagementCredentials credentials = new ManagementCredentials(
                ManagementAccess.username(environment), ManagementAccess.password(environment));
        MapReactiveUserDetailsService users = new MapReactiveUserDetailsService(credentials.user());
        ReactiveAuthenticationManager manager =
                authenticationManager(users, credentials);

        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .authenticationManager(manager)
                .httpBasic(basic -> { })
                .authorizeExchange(exchanges -> exchanges
                        .matchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyExchange().authenticated())
                .build();
    }

    private ReactiveAuthenticationManager authenticationManager(MapReactiveUserDetailsService users,
                                                                ManagementCredentials credentials) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(users);
        manager.setPasswordEncoder(credentials.encoder());
        return manager;
    }
}
