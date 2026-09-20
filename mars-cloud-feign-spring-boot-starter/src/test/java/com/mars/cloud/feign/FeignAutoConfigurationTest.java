package com.mars.cloud.feign;

import com.mars.cloud.feign.autoconfigure.MarsFeignAutoConfiguration;
import com.mars.cloud.feign.autoconfigure.MarsFeignDefaultsEnvironmentPostProcessor;
import feign.Capability;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class FeignAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MarsFeignAutoConfiguration.class));

    @Test
    void autoConfigurationProvidesSafeDefaultsAndCapability() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Retryer.class)).isSameAs(Retryer.NEVER_RETRY);
            assertThat(context.getBean(Request.Options.class).connectTimeoutMillis()).isEqualTo(1000);
            assertThat(context.getBean(Request.Options.class).readTimeoutMillis()).isEqualTo(3000);
            assertThat(context).hasSingleBean(RequestInterceptor.class);
            assertThat(context).hasSingleBean(Capability.class);
        });
    }

    @Test
    void timeoutCannotBeRelaxed() {
        contextRunner
                .withPropertyValues("spring.cloud.openfeign.client.config.orders.read-timeout=3001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("1..3000ms");
                });

        contextRunner
                .withBean(Request.Options.class, () -> new Request.Options(0, 3000))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("1..1000ms");
                });
    }

    @Test
    void directUrlIsRejectedForInternalClient() {
        contextRunner
                .withPropertyValues("spring.cloud.openfeign.client.config.orders.url=http://example.test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("不得配置 URL");
                });
    }

    @Test
    void feignRetryerConfigurationIsRejected() {
        contextRunner
                .withPropertyValues("spring.cloud.openfeign.client.config.orders.retryer=example.CustomRetryer")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("不得配置 Retryer");
                });
    }

    @Test
    void loadBalancerRetryCannotExpandToWritesOrSameInstance() {
        contextRunner
                .withPropertyValues("spring.cloud.loadbalancer.retry.retry-on-all-operations=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("不得给写请求开启重试");
                });

        contextRunner
                .withPropertyValues("spring.cloud.loadbalancer.retry.max-retries-on-same-service-instance=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("不得重试同一个实例");
                });
    }

    @Test
    void loadBalancerRetryCanBeDisabledOrReduced() {
        contextRunner
                .withPropertyValues(
                        "spring.cloud.loadbalancer.retry.enabled=false",
                        "spring.cloud.loadbalancer.retry.max-retries-on-next-service-instance=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void retryableStatusesCannotExpandPastGatewayFailures() {
        contextRunner
                .withPropertyValues("spring.cloud.loadbalancer.retry.retryable-status-codes=502,503,504,505")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("只能是 502、503、504 的子集");
                });
    }

    @Test
    void environmentPostProcessorAddsLowPriorityDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        MarsFeignDefaultsEnvironmentPostProcessor processor = new MarsFeignDefaultsEnvironmentPostProcessor();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.default.connect-timeout"))
                .isEqualTo("1000");
        assertThat(environment.getProperty("spring.cloud.openfeign.client.config.default.read-timeout"))
                .isEqualTo("3000");
        assertThat(environment.getProperty("spring.cloud.loadbalancer.retry.retryable-status-codes"))
                .isEqualTo("502,503,504");
    }
}
