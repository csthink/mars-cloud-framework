package com.mars.cloud.observability.app.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** 响应式栈的被测应用，与 Servlet 栈那个对应，同样排除 Spring Boot 的默认用户装配。 */
@SpringBootApplication(exclude = ReactiveUserDetailsServiceAutoConfiguration.class)
@RestController
public class ReactiveProbeApplication {

    /** 线程切换之后写出的日志行里带这个标记，用例按它找到那一行。 */
    public static final String AFTER_THREAD_SWITCH_MARKER = "after-thread-switch-marker";

    private static final Logger log = LoggerFactory.getLogger(ReactiveProbeApplication.class);

    @GetMapping("/business")
    String business() {
        return "business-ok";
    }

    /**
     * {@code Mono.delay} 在 Reactor 的 parallel 调度器上发出元素，日志因此写在另一个线程上，
     * 那个线程的 MDC 里本来没有当前请求的 traceId。
     */
    @GetMapping("/business/after-thread-switch")
    Mono<String> afterThreadSwitch() {
        return Mono.delay(Duration.ofMillis(10)).map(ignored -> {
            log.info(AFTER_THREAD_SWITCH_MARKER);
            return "after-thread-switch-ok";
        });
    }

    @Bean
    SecurityWebFilterChain businessSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
