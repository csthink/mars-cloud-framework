package com.mars.cloud.observability.app.defaults.reactive;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 响应式栈的被测应用，与 Servlet 栈那个对应：自己不声明安全链。 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@RestController
public class DefaultSecurityReactiveProbeApplication {

    @GetMapping("/business")
    String business() {
        return "business-ok";
    }
}
