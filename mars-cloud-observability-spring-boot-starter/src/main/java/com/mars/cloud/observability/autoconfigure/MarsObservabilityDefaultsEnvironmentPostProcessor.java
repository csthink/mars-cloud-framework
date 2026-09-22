package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.internal.ManagementPort;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 以最低优先级提供可观测性的默认值，并把管理端口从业务端口推导出来。
 *
 * <p>这里写入的每一项都会被显式配置（环境变量、配置中心、application.yml）覆盖，
 * 覆盖后的取值由 {@link ObservabilityConventionVerifier} 在启动期核验。
 */
public final class MarsObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "marsObservabilityDefaults";
    /** Boot 4 的 OTLP 追踪导出端点属性名；Boot 3 的 management.otlp.tracing.endpoint 已失效。 */
    public static final String TRACING_ENDPOINT_PROPERTY =
            "management.opentelemetry.tracing.export.otlp.endpoint";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();

        // 管理端点的暴露面必须在 Actuator 读取它之前定好，所以收窄在这里完成而不是留给核验器：
        // 没有凭据或没有 Spring Security 时，除 health 与 info 外的端点在管理端口上是无认证可读的。
        defaults.put("management.endpoints.web.exposure.include", effectiveExposure(environment));
        defaults.put("management.endpoint.health.show-details", "when-authorized");
        defaults.put("management.endpoint.health.show-components", "when-authorized");
        defaults.put("management.endpoint.health.probes.enabled", true);
        defaults.put("management.metrics.tags.application", "${spring.application.name:unknown}");

        // 链路追踪：全量采样，生产按流量在配置中心调低；导出端点由环境变量给出。
        // 属性名用 Boot 4 的前缀。Boot 3 的 management.otlp.tracing.* 在 4.0 已按 error 级废弃，
        // 但配上去既不报错也不生效，导出器会静默地不创建，所以这里不能沿用旧名。
        defaults.put("management.tracing.sampling.probability", "1.0");
        String endpoint = environment.getProperty("OTLP_TRACING_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            defaults.put(TRACING_ENDPOINT_PROPERTY, endpoint);
        }

        // 结构化日志：默认 ECS JSON 到控制台；plain 只在本机调试时用，那时不写这个键。
        String consoleFormat = environment.getProperty("mars.observability.logging.console-format", "ecs");
        if (!"plain".equalsIgnoreCase(consoleFormat)) {
            defaults.put("logging.structured.format.console", "ecs");
        }

        Integer derived = deriveManagementPort(environment);
        if (derived != null) {
            defaults.put("management.server.port", derived);
        }

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /** 能认证就用完整清单，否则退回受限清单。 */
    private String effectiveExposure(ConfigurableEnvironment environment) {
        boolean authenticated = ManagementAccess.canAuthenticate(environment, getClass().getClassLoader());
        String key = authenticated
                ? "mars.observability.management.exposure"
                : "mars.observability.management.restricted-exposure";
        String fallback = authenticated ? "health,info,prometheus,metrics,loggers,threaddump,heapdump" : "health,info";
        return environment.getProperty(key, fallback);
    }

    /**
     * 只有在没人显式配置管理端口时才推导。显式配置保留原值，由核验器判断它是否符合约定，
     * 这样「配错了」是一条可读的启动失败，而不是被默认值悄悄改掉。
     */
    private Integer deriveManagementPort(ConfigurableEnvironment environment) {
        if (environment.getProperty("management.server.port") != null) {
            return null;
        }
        int offset = environment.getProperty("mars.observability.management.port-offset", Integer.class, 1000);
        return ManagementPort.derive(ManagementPort.serverPort(environment), offset);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
