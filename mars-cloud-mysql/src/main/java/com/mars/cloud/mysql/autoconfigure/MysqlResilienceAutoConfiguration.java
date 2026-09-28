package com.mars.cloud.mysql.autoconfigure;

import com.mars.cloud.core.resilience.ConfigurationRefreshGuard;
import com.mars.cloud.mysql.resilience.MysqlResilienceLimits;
import com.mars.cloud.mysql.resilience.ObservedDataSource;
import com.mars.cloud.mysql.resilience.SlowQueryListener;
import java.time.Duration;
import java.sql.SQLException;
import org.springframework.beans.factory.ObjectProvider;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(beforeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public final class MysqlResilienceAutoConfiguration {
    @Bean
    static BeanPostProcessor observedDataSources(Environment environment) {
        return new DestructionAwareBeanPostProcessor() {
            private final java.util.Map<Object,ObservedDataSource> owned=new java.util.IdentityHashMap<>();
            @Override public boolean requiresDestruction(Object bean) { synchronized(owned) {return owned.containsKey(bean);} }
            @Override public void postProcessBeforeDestruction(Object bean,String name) {
                ObservedDataSource source;
                synchronized(owned) {source=owned.remove(bean);}
                if(source!=null) try {source.close();} catch(Exception failure) {throw new IllegalStateException("Cannot close observed datasource");}
            }
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource dataSource)) { return bean; }
                Duration threshold = MysqlResilienceLimits.validateEnvironment(environment);
                MysqlResilienceLimits.validateDataSource(dataSource);
                try {
                    if (dataSource.isWrapperFor(ObservedDataSource.class)) { return dataSource; }
                } catch (SQLException failure) {
                    throw new IllegalStateException("Cannot inspect datasource observation wrapper");
                }
                if (dataSource instanceof TransactionAwareDataSourceProxy transactional) {
                    ObservedDataSource observed=new ObservedDataSource(transactional.getTargetDataSource(),new SlowQueryListener(name,threshold));
                    transactional.setTargetDataSource(observed);
                    synchronized(owned) {owned.put(bean,observed);}
                    return bean;
                }
                return new ObservedDataSource(dataSource, new SlowQueryListener(name, threshold));
            }
        };
    }

    @Bean
    ConfigurationRefreshGuard datasourceRefreshGuard(Environment environment, ObjectProvider<DataSource> sources) {
        return new ConfigurationRefreshGuard(() -> {
            if (sources.stream().findAny().isPresent()) { MysqlResilienceLimits.validateEnvironment(environment); }
        });
    }
}
