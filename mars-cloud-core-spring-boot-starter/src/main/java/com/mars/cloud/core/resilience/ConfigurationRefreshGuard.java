package com.mars.cloud.core.resilience;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;

/** Validate changed environment values before Spring Cloud rebinds existing objects. */
public final class ConfigurationRefreshGuard implements GenericApplicationListener {
    private final Runnable validation;

    public ConfigurationRefreshGuard(Runnable validation) {
        this.validation = validation;
    }

    @Override
    public boolean supportsEventType(ResolvableType type) {
        Class<?> event = type.resolve();
        return event != null && event.getName().equals(
                "org.springframework.cloud.context.environment.EnvironmentChangeEvent");
    }

    @Override
    public boolean supportsAsyncExecution() { return false; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public void onApplicationEvent(ApplicationEvent event) { validation.run(); }
}
