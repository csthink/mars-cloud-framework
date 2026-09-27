package com.mars.cloud.mysql.resilience;

import com.mars.cloud.core.resilience.ResilienceSettings;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

public final class MysqlResilienceLimits {
    private MysqlResilienceLimits() { }

    public static Duration validateEnvironment(Environment environment) {
        int maximum = number(environment, "maximum-pool-size", 10);
        int minimum = number(environment, "minimum-idle", maximum);
        validate(maximum, minimum, number(environment, "connection-timeout", 1000),
                number(environment, "validation-timeout", 500));
        return ResilienceSettings.duration(environment, "mars.datasource.slow-query-threshold", Duration.ofSeconds(1));
    }

    private static int number(Environment environment, String key, int fallback) {
        try { return environment.getProperty("spring.datasource.hikari." + key, Integer.class, fallback); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Invalid spring.datasource.hikari." + key); }
    }

    public static HikariDataSource validateDataSource(DataSource dataSource) {
        HikariDataSource pool;
        try {
            pool = dataSource instanceof HikariDataSource hikari ? hikari : dataSource.unwrap(HikariDataSource.class);
        }
        catch (SQLException | RuntimeException invalid) {
            throw new IllegalStateException("DataSource must expose a HikariDataSource through unwrap");
        }
        if (pool == null) { throw new IllegalStateException("DataSource must expose a HikariDataSource through unwrap"); }
        int maximum = pool.getMaximumPoolSize() == -1 ? 10 : pool.getMaximumPoolSize();
        int minimum = pool.getMinimumIdle() == -1 ? maximum : pool.getMinimumIdle();
        validate(maximum, minimum, pool.getConnectionTimeout(), pool.getValidationTimeout());
        return pool;
    }

    private static void validate(int maximum, int minimum, long connection, long validation) {
        require(maximum >= 1 && maximum <= 10, "maximum-pool-size must be between 1 and 10");
        require(minimum >= 0 && minimum <= maximum, "minimum-idle must be between 0 and maximum-pool-size");
        require(connection >= 251 && connection <= 1000, "connection-timeout must be between 251 and 1000 milliseconds");
        require(validation >= 250 && validation <= 500 && validation < connection,
                "validation-timeout must be between 250 and 500 milliseconds and less than connection-timeout");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) { throw new IllegalStateException("spring.datasource.hikari." + detail); }
    }
}
