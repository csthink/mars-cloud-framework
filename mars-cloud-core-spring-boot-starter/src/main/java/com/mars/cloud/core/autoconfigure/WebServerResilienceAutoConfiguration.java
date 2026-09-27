package com.mars.cloud.core.autoconfigure;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ConfigurableWebServerFactory.class)
public final class WebServerResilienceAutoConfiguration {
    @Bean
    static BeanPostProcessor webServerShutdownLimits() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof ConfigurableWebServerFactory) {
                    if (!(bean instanceof AbstractConfigurableWebServerFactory factory)
                            || factory.getShutdown() != Shutdown.GRACEFUL) {
                        throw new IllegalStateException("Web server factory must use graceful shutdown");
                    }
                }
                return bean;
            }
        };
    }
}
