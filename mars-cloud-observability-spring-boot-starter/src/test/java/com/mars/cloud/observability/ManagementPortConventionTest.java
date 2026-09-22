package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.autoconfigure.ObservabilityConventionVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口的推导与核验：推导只在没人显式配置时发生，配错了是一条可读的启动失败。
 */
class ManagementPortConventionTest {

    /** 业务端口加默认偏移量就是管理端口。 */
    @Test void derivesManagementPortFromServerPort() {
        assertThat(managementPortAfterPostProcessing(Map.of("server.port", "8103"))).isEqualTo("9103");
    }

    /** 偏移量可配，管理端口等于业务端口加配置的偏移量。 */
    @Test void honoursConfiguredOffset() {
        assertThat(managementPortAfterPostProcessing(
                Map.of("server.port", "8203", "mars.observability.management.port-offset", "1000")))
                .isEqualTo("9203");
    }

    /** 显式配置保留原值，由核验器判断它是否符合约定，而不是被默认值悄悄改掉。 */
    @Test void keepsExplicitManagementPort() {
        assertThat(managementPortAfterPostProcessing(
                Map.of("server.port", "8103", "management.server.port", "9103")))
                .isEqualTo("9103");
    }

    /** 业务端口为随机端口时不推导，管理端点留在业务端口上。 */
    @Test void doesNotDeriveForRandomServerPort() {
        assertThat(managementPortAfterPostProcessing(Map.of("server.port", "0"))).isNull();
    }

    /** 没有配置业务端口时同样不推导。 */
    @Test void doesNotDeriveWithoutServerPort() {
        assertThat(managementPortAfterPostProcessing(Map.of())).isNull();
    }

    /** 端口与约定不符时启动失败，消息写出期望值与实际值。 */
    @Test void rejectsManagementPortThatBreaksTheConvention() {
        runner("server.port=8103", "management.server.port=9999",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=secret")
                .run(failsWith("期望 9103", "实际 9999"));
    }

    /** 禁用管理端口会让实例监控看不到本实例，因此拒绝。 */
    @Test void rejectsDisabledManagementPort() {
        runner("server.port=8103", "management.server.port=-1",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=secret")
                .run(failsWith("不能为 -1"));
    }

    /** 共用端口只允许开发 profile。 */
    @Test void rejectsSamePortOutsideDevelopmentProfiles() {
        runner("server.port=8103", "mars.observability.management.port-offset=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=secret")
                .run(failsWith("只允许开发 profile"));
    }

    /** 开发 profile 下共用端口是允许的，MockMvc 类测试要用到。 */
    @Test void allowsSamePortInDevelopmentProfile() {
        runner("server.port=8103", "mars.observability.management.port-offset=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=secret",
                "mars.env.dev-profiles=local,test")
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ObservabilityConventionVerifier.class));
    }

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    private static ContextConsumer<AssertableApplicationContext> failsWith(String... fragments) {
        return context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> {
                        for (String fragment : fragments) {
                            assertThat(failure.getMessage()).contains(fragment);
                        }
                    });
        };
    }

    /** 直接驱动环境后处理器，断言它写进环境的管理端口。 */
    private static String managementPortAfterPostProcessing(Map<String, Object> properties) {
        MockEnvironment mock = new MockEnvironment();
        properties.forEach((key, value) -> mock.setProperty(key, String.valueOf(value)));
        ConfigurableEnvironment environment = mock;
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("management.server.port");
    }
}
