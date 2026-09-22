package com.mars.cloud.observability.app.reactive;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 响应式栈的被测应用，与 Servlet 栈那个对应。 */
@SpringBootApplication
@RestController
public class ReactiveProbeApplication {

    @GetMapping("/business")
    String business() {
        return "business-ok";
    }

    @Bean
    SecurityWebFilterChain businessSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
