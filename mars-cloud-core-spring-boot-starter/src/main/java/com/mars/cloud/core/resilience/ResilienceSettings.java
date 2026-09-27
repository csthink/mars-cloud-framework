package com.mars.cloud.core.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Startup limits shared by applications, without web-stack or discovery dependencies. */
public final class ResilienceSettings {
    private ResilienceSettings() { }

    public static Duration duration(Environment environment, String key, Duration maximum) {
        try {
            Duration value = Binder.get(environment).bind(key, Duration.class).orElse(maximum);
            if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
                throw new IllegalArgumentException();
            }
            return value;
        }
        catch (RuntimeException invalid) {
            throw new IllegalStateException(key + " must be positive and no greater than " + maximum);
        }
    }

    public static Duration shutdownTimeout(Environment environment) {
        if (!"graceful".equalsIgnoreCase(environment.getProperty("server.shutdown", "graceful"))) {
            throw new IllegalStateException("server.shutdown must be graceful");
        }
        return duration(environment, "spring.lifecycle.timeout-per-shutdown-phase", Duration.ofSeconds(30));
    }
}
