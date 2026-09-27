package com.mars.cloud.core.autoconfigure;

import com.mars.cloud.core.resilience.BoundedLifecycleProcessor;
import com.mars.cloud.core.resilience.ConfigurationRefreshGuard;
import com.mars.cloud.core.resilience.ResilienceSettings;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration")
public final class ResilienceAutoConfiguration {
    @Bean(name = "lifecycleProcessor")
    @ConditionalOnMissingBean(name = "lifecycleProcessor")
    BoundedLifecycleProcessor lifecycleProcessor(Environment environment) {
        BoundedLifecycleProcessor processor = new BoundedLifecycleProcessor();
        processor.setTimeoutPerShutdownPhase(ResilienceSettings.shutdownTimeout(environment).toMillis());
        return processor;
    }

    @Bean
    static BeanPostProcessor lifecycleLimits(Environment environment) {
        ResilienceSettings.shutdownTimeout(environment);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof LifecycleProcessor && !(bean instanceof BoundedLifecycleProcessor)) {
                    throw new IllegalStateException("Custom lifecycleProcessor must extend BoundedLifecycleProcessor");
                }
                return bean;
            }
        };
    }

    @Bean
    ConfigurationRefreshGuard shutdownRefreshGuard(Environment environment) {
        return new ConfigurationRefreshGuard(() -> ResilienceSettings.shutdownTimeout(environment));
    }
}
