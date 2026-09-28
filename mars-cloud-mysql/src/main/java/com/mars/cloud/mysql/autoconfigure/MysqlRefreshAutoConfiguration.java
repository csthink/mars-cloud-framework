package com.mars.cloud.mysql.autoconfigure;

import com.mars.cloud.mysql.resilience.DataSourcePreservingRebinder;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration.RefreshProperties;
import org.springframework.cloud.context.properties.ConfigurationPropertiesBeans;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.annotation.Bean;

/** Keeps pool ownership unchanged when cloud configuration is refreshed. */
@AutoConfiguration(beforeName="org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration")
@ConditionalOnClass(ConfigurationPropertiesRebinder.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="spring.cloud.refresh.enabled",matchIfMissing=true)
public class MysqlRefreshAutoConfiguration {
    @Bean @ConditionalOnMissingBean(ConfigurationPropertiesRebinder.class)
    DataSourcePreservingRebinder poolPreservingRebinder(ConfigurationPropertiesBeans beans,ObjectProvider<RefreshProperties> properties) {
        return new DataSourcePreservingRebinder(beans,properties.getIfAvailable(RefreshProperties::new));
    }
    @Bean SmartInitializingSingleton datasourceRebinderValidation(ObjectProvider<DataSource> sources,
            ObjectProvider<ConfigurationPropertiesRebinder> rebinders) {
        return ()->{
            if(sources.stream().findAny().isPresent() && rebinders.stream().anyMatch(bean->!(bean instanceof DataSourcePreservingRebinder)))
                throw new IllegalStateException("Custom ConfigurationPropertiesRebinder must extend DataSourcePreservingRebinder");
        };
    }
}
