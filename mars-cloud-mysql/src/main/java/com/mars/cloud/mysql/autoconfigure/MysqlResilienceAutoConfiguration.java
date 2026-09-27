package com.mars.cloud.mysql.autoconfigure;

import com.mars.cloud.core.resilience.ConfigurationRefreshGuard;
import com.mars.cloud.mysql.resilience.MysqlResilienceLimits;
import com.mars.cloud.mysql.resilience.ObservedDataSource;
import com.mars.cloud.mysql.resilience.SlowQueryListener;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(beforeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public final class MysqlResilienceAutoConfiguration {
    @Bean
    static BeanPostProcessor observedDataSources(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource dataSource)) { return bean; }
                Duration threshold = MysqlResilienceLimits.validateEnvironment(environment);
                MysqlResilienceLimits.validateDataSource(dataSource);
                return dataSource instanceof ObservedDataSource ? dataSource
                        : new ObservedDataSource(dataSource, new SlowQueryListener(name, threshold));
            }
        };
    }

    @Bean
    ConfigurationRefreshGuard datasourceRefreshGuard(Environment environment) {
        return new ConfigurationRefreshGuard(() -> MysqlResilienceLimits.validateEnvironment(environment));
    }
}
