package com.mars.cloud.security;

import com.mars.cloud.security.servlet.MarsServletSecurityConfigurer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

class CustomServletHttpSecurityTest extends HttpSecurityContract {
    protected Class<?> application() { return App.class; }
    protected WebApplicationType stack() { return WebApplicationType.SERVLET; }
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @Import(ServletHttpSecurityTest.Endpoints.class)
    static class App {
        @Bean PdpClient pdp() { return (caller, action, resource) -> decision(action); }
        @Bean SecurityFilterChain customChain(HttpSecurity http, MarsServletSecurityConfigurer configurer) throws Exception {
            http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
            return configurer.configure(http).build();
        }
    }
}
