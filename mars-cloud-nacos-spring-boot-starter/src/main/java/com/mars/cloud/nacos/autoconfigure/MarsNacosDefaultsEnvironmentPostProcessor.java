package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 以最低优先级提供 Nacos 相关的默认值；任何显式配置（环境变量、配置中心、application.yml）都覆盖它。
 *
 * <p>关闭 Spring Cloud 的注册中心健康检查（{@code discoveryComposite}）。停机时 Nacos 的优雅停机先注销并关闭
 * Nacos 客户端，再按 {@code spring.cloud.nacos.discovery.graceful-shutdown-wait-time}（默认 10 秒）等待；
 * 等待期间有人查询健康端点（例如实例监控的轮询），这项检查查询注册中心，Nacos 客户端就被重新创建，
 * 停机中途重新连接 Nacos。这项检查只在根健康端点里，不在存活与就绪探针组里。
 */
public final class MarsNacosDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "marsNacosDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of("spring.cloud.discovery.client.composite-indicator.enabled", false)));
    }
}
