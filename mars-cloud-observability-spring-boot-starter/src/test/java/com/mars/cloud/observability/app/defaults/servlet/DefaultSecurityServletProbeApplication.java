package com.mars.cloud.observability.app.defaults.servlet;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servlet 栈的被测应用，自己不声明安全链，业务请求由 Spring Boot 的默认安全链保护。
 * 排除默认用户装配的理由与 {@code ServletProbeApplication} 相同。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@RestController
public class DefaultSecurityServletProbeApplication {

    @GetMapping("/business")
    String business() {
        return "business-ok";
    }
}
