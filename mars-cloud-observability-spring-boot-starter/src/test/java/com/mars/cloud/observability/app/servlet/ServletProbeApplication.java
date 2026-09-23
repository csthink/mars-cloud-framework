package com.mars.cloud.observability.app.servlet;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servlet 栈的被测应用。它自带一条放行业务请求的安全链，用来证明业务链与管理链共存：
 * 部署物接入本组件后，自己的安全策略不受影响。
 *
 * <p>业务链放行全部请求，不需要用户存储，所以排除 Spring Boot 的默认用户装配，与部署物一致：
 * 部署物用 JWT 资源服务器，那项装配自行退出。不排除时 Spring Boot 生成一个临时口令并打一条告警。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@RestController
public class ServletProbeApplication {

    @GetMapping("/business")
    String business() {
        return "business-ok";
    }

    @Bean
    SecurityFilterChain businessSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }
}
