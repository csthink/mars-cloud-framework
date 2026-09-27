package com.mars.cloud.core.autoconfigure;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Lowest-priority defaults; explicit application settings remain visible to validation. */
public final class ResilienceDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains("marsResilienceDefaults")) {
            environment.getPropertySources().addLast(new MapPropertySource("marsResilienceDefaults", Map.of(
                    "server.shutdown", "graceful", "spring.lifecycle.timeout-per-shutdown-phase", "30s")));
        }
    }
}
