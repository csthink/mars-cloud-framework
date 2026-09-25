package com.mars.cloud.sentinel.servlet;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Servlet 用例的应用：两个接口与一个模拟统一异常处理（把拦截异常写成 429）。规则来自内存。
 *
 * <p>两个内部类带 {@code @RestController} 与 {@code @RestControllerAdvice}，作为配置类的成员类自动登记。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class ServletProbeApplication {

    static final String APPLICATION = "servlet-probe";
    static final InMemoryRuleConfigSource RULES = new InMemoryRuleConfigSource();

    @Bean
    RuleConfigSource ruleConfigSource() {
        return RULES;
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/{id}")
        String probe(@PathVariable String id) {
            return "probe " + id;
        }

        @PostMapping("/probe/{id}")
        String update(@PathVariable String id) {
            return "updated " + id;
        }

        @GetMapping("/open")
        String open() {
            return "open";
        }
    }

    @RestControllerAdvice
    static class ProbeAdvice {

        @ExceptionHandler(BlockException.class)
        ResponseEntity<String> blocked(BlockException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("blocked:" + ex.getClass().getSimpleName() + ":" + ex.getRule().getResource());
        }
    }
}
