package com.mars.cloud.mysql.env;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class MysqlResilienceDefaults implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains("marsMysqlResilienceDefaults")) {
            environment.getPropertySources().addLast(new MapPropertySource("marsMysqlResilienceDefaults", Map.of(
                    "spring.datasource.hikari.maximum-pool-size", 10,
                    "spring.datasource.hikari.connection-timeout", 1000,
                    "spring.datasource.hikari.validation-timeout", 500,
                    "spring.datasource.hikari.register-mbeans", false,
                    "mars.datasource.slow-query-threshold", "1s")));
        }
    }
}
