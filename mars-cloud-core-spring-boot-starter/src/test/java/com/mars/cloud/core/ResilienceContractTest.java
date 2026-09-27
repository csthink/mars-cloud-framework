package com.mars.cloud.core;

import com.mars.cloud.core.autoconfigure.ResilienceAutoConfiguration;
import com.mars.cloud.core.autoconfigure.ResilienceDefaultsEnvironmentPostProcessor;
import com.mars.cloud.core.autoconfigure.WebServerResilienceAutoConfiguration;
import com.mars.cloud.core.resilience.BoundedLifecycleProcessor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilienceContractTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class));

    @Test
    void defaultsDoNotHideAnExplicitOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("explicit", Map.of("server.shutdown", "immediate")));
        ResilienceDefaultsEnvironmentPostProcessor defaults = new ResilienceDefaultsEnvironmentPostProcessor();
        defaults.postProcessEnvironment(environment, new SpringApplication());
        defaults.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("immediate");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.shutdown=immediate", "spring.lifecycle.timeout-per-shutdown-phase=31s",
            "spring.lifecycle.timeout-per-shutdown-phase=0", "spring.lifecycle.timeout-per-shutdown-phase=-1s"})
    void rejectsRelaxedOrInvalidShutdownSettings(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void acceptsASmallerPhaseBudgetAndGuardsActualProcessorMutations() {
        runner.withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase=2s").run(context -> {
            assertThat(context).hasNotFailed();
            BoundedLifecycleProcessor processor = context.getBean(BoundedLifecycleProcessor.class);
            assertThatThrownBy(() -> processor.setTimeoutPerShutdownPhase(30_001)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> processor.setTimeoutForShutdownPhase(100, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> processor.setTimeoutsForShutdownPhases(Map.of(100, 31_000L)))
                    .isInstanceOf(IllegalArgumentException.class);
            processor.setTimeoutForShutdownPhase(100, 1000);
        });
    }

    @Test
    void rejectsAnUnverifiableCustomLifecycleProcessor() {
        runner.withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, DefaultLifecycleProcessor::new)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void checksActualWebFactoryRatherThanOnlyEnvironmentText() {
        AbstractConfigurableWebServerFactory factory = mock(AbstractConfigurableWebServerFactory.class);
        when(factory.getShutdown()).thenReturn(Shutdown.IMMEDIATE);
        runner.withConfiguration(AutoConfigurations.of(WebServerResilienceAutoConfiguration.class))
                .withBean(AbstractConfigurableWebServerFactory.class, () -> factory)
                .withPropertyValues("server.shutdown=graceful")
                .run(context -> assertThat(context).hasFailed());
        when(factory.getShutdown()).thenReturn(Shutdown.GRACEFUL);
        runner.withConfiguration(AutoConfigurations.of(WebServerResilienceAutoConfiguration.class))
                .withBean(AbstractConfigurableWebServerFactory.class, () -> factory)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
