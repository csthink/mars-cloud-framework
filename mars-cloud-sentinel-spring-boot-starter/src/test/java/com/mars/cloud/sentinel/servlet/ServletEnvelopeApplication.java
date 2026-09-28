package com.mars.cloud.sentinel.servlet;

import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootConfiguration
@EnableAutoConfiguration
class ServletEnvelopeApplication {

    static final String APPLICATION = "servlet-envelope-probe";
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

        @GetMapping("/open")
        String open() {
            return "open";
        }
    }
}
