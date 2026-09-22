package com.mars.cloud.observability.app.servlet;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servlet 栈的被测应用。它自带一条放行业务请求的安全链，用来证明业务链与管理链共存：
 * 部署物接入本组件后，自己的安全策略不受影响。
 */
@SpringBootApplication
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
