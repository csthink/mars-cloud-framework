package com.mars.cloud.sentinel.autoconfigure;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 与约定冲突的 Sentinel 配置在环境准备阶段就让启动失败；默认值只在最低优先级。
 */
class EnvironmentConventionTest {

    private final MarsSentinelEnvironmentPostProcessor processor = new MarsSentinelEnvironmentPostProcessor();

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.cloud.sentinel.transport.dashboard",
            "spring.cloud.sentinel.transport.port",
            "spring.cloud.sentinel.datasource.flow.nacos.data-id",
            "spring.cloud.sentinel.scg.fallback.mode",
            "spring.cloud.sentinel.block-page",
            "spring.cloud.sentinel.servlet.block-page"
    })
    void refusesSettingsThatBypassTheConvention(String property) {
        StandardEnvironment environment = environment(Map.of(property, "x"));
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application(WebApplicationType.SERVLET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property);
    }

    @Test
    void refusesTheDashboardGivenAsAnEnvironmentVariable() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SPRING_CLOUD_SENTINEL_TRANSPORT_DASHBOARD", "127.0.0.1:8080")));
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application(WebApplicationType.SERVLET)))
                .hasMessageContaining("spring.cloud.sentinel.transport.dashboard");
    }

    @Test
    void refusesTheAlibabaFeignIntegration() {
        StandardEnvironment environment = environment(Map.of("feign.sentinel.enabled", "true"));
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application(WebApplicationType.SERVLET)))
                .hasMessageContaining("feign.sentinel.enabled");
    }

    @Test
    void appliesDefaultsAtTheLowestPrecedence() {
        StandardEnvironment environment = environment(Map.of("spring.cloud.sentinel.http-method-specify", "false"));
        processor.postProcessEnvironment(environment, application(WebApplicationType.SERVLET));

        assertThat(environment.getProperty("spring.cloud.sentinel.http-method-specify")).isEqualTo("false");
        assertThat(environment.getProperty("spring.cloud.sentinel.scg.enabled")).isEqualTo("false");
        assertThat(System.getProperty(MarsSentinelEnvironmentPostProcessor.METRIC_FLUSH_INTERVAL)).isEqualTo("0");
    }

    @Test
    void keepsTheGatewayFilterInReactiveApplications() {
        StandardEnvironment environment = environment(Map.of());
        processor.postProcessEnvironment(environment, application(WebApplicationType.REACTIVE));

        assertThat(environment.getProperty("spring.cloud.sentinel.http-method-specify")).isEqualTo("true");
        assertThat(environment.containsProperty("spring.cloud.sentinel.scg.enabled")).isFalse();
    }

    @Test
    void theConfiguredApplicationTypeWinsOverTheDeducedOne() {
        StandardEnvironment environment = environment(Map.of("spring.main.web-application-type", "reactive"));
        processor.postProcessEnvironment(environment, application(WebApplicationType.SERVLET));
        assertThat(environment.containsProperty("spring.cloud.sentinel.scg.enabled")).isFalse();
    }

    @Test
    void doesNothingElseWhenSentinelIsExplicitlyDisabled() {
        StandardEnvironment environment = environment(Map.of("spring.cloud.sentinel.enabled", "false",
                "spring.cloud.sentinel.transport.dashboard", "ignored"));
        processor.postProcessEnvironment(environment, application(WebApplicationType.REACTIVE));
        assertThat(environment.getProperty("spring.cloud.sentinel.scg.enabled")).isEqualTo("false");
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("probe", properties));
        return environment;
    }

    private static SpringApplication application(WebApplicationType type) {
        SpringApplication application = new SpringApplication();
        application.setWebApplicationType(type);
        return application;
    }
}
