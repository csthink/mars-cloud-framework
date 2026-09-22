package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端点的暴露面：只有凭据与 Spring Security 都具备时才放开指标一类的端点。
 *
 * <p>本模块的测试 classpath 上有 Spring Security，所以「缺凭据」与「凭据齐备」两种情况
 * 都能在这里覆盖；「没有 Spring Security」那条由 {@link DependencyBoundaryTest} 的类存在性断言
 * 与运行期的收窄逻辑共同保证。
 */
class ManagementExposureTest {

    /** 凭据齐备：完整清单。 */
    @Test void exposesFullSetWhenCredentialsArePresent() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops",
                "mars.observability.management.password", "secret")))
                .isEqualTo("health,info,prometheus,metrics,loggers,threaddump,heapdump");
    }

    /** 缺密码：收窄。只有用户名不构成凭据。 */
    @Test void narrowsWhenPasswordIsMissing() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops")))
                .isEqualTo("health,info");
    }

    /** 两项都缺：收窄。 */
    @Test void narrowsWhenCredentialsAreMissing() {
        assertThat(exposureAfterPostProcessing(Map.of())).isEqualTo("health,info");
    }

    /** 空白值不算凭据。 */
    @Test void treatsBlankCredentialsAsMissing() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "  ",
                "mars.observability.management.password", "secret")))
                .isEqualTo("health,info");
    }

    /** 两个清单都可以覆盖。 */
    @Test void honoursConfiguredExposureLists() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops",
                "mars.observability.management.password", "secret",
                "mars.observability.management.exposure", "health,prometheus")))
                .isEqualTo("health,prometheus");
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.restricted-exposure", "health")))
                .isEqualTo("health");
    }

    /** 缺凭据时非开发 profile 启动失败，消息说清楚缺什么。 */
    @Test void failsOutsideDevelopmentProfilesWithoutCredentials() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("管理端点缺少认证凭据")
                            .hasMessageContaining("mars.observability.management.username");
                });
    }

    /** 缺凭据时开发 profile 只告警，上下文照常启动。 */
    @Test void warnsInsteadOfFailingInDevelopmentProfiles() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.env.dev-profiles=local,test", "spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 健康明细按授权显示：匿名只看到聚合状态。 */
    @Test void showsHealthDetailsOnlyToAuthorizedCallers() {
        MockEnvironment environment = new MockEnvironment();
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("when-authorized");
        assertThat(environment.getProperty("management.endpoint.health.show-components")).isEqualTo("when-authorized");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
    }

    private static String exposureAfterPostProcessing(Map<String, Object> properties) {
        MockEnvironment mock = new MockEnvironment();
        properties.forEach((key, value) -> mock.setProperty(key, String.valueOf(value)));
        ConfigurableEnvironment environment = mock;
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("management.endpoints.web.exposure.include");
    }
}
