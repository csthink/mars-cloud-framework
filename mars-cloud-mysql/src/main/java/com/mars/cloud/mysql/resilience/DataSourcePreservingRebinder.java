package com.mars.cloud.mysql.resilience;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration.RefreshProperties;
import org.springframework.cloud.context.properties.ConfigurationPropertiesBeans;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.context.ApplicationContext;

/** Refreshes ordinary configuration while preserving initialized pools and their delegates. */
public class DataSourcePreservingRebinder extends ConfigurationPropertiesRebinder {
    private ApplicationContext context;
    public DataSourcePreservingRebinder(ConfigurationPropertiesBeans beans,RefreshProperties properties) { super(beans,properties); }
    @Override public void setApplicationContext(ApplicationContext context) {super.setApplicationContext(context);this.context=context;}
    @Override public boolean rebind(String name) {
        if(context.containsBean(name) && context.getBean(name) instanceof DataSource) return false;
        return super.rebind(name);
    }
    @Override public Set<String> getNeverRefreshable() {
        Set<String> types=new HashSet<>(super.getNeverRefreshable());
        types.add(HikariDataSource.class.getName());types.add(ObservedDataSource.class.getName());return types;
    }
}
