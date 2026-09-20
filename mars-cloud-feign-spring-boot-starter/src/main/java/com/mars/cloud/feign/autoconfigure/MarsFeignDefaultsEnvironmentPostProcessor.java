package com.mars.cloud.feign.autoconfigure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以最低优先级提供 Feign 与 LoadBalancer 的安全默认值。
 */
public final class MarsFeignDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "marsFeignDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.cloud.openfeign.client.config.default.connect-timeout", 1000);
        defaults.put("spring.cloud.openfeign.client.config.default.read-timeout", 3000);
        defaults.put("spring.cloud.loadbalancer.retry.enabled", true);
        defaults.put("spring.cloud.loadbalancer.retry.max-retries-on-same-service-instance", 0);
        defaults.put("spring.cloud.loadbalancer.retry.max-retries-on-next-service-instance", 1);
        defaults.put("spring.cloud.loadbalancer.retry.retry-on-all-operations", false);
        defaults.put("spring.cloud.loadbalancer.retry.avoid-previous-instance", true);
        defaults.put("spring.cloud.loadbalancer.retry.retryable-status-codes", "502,503,504");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
