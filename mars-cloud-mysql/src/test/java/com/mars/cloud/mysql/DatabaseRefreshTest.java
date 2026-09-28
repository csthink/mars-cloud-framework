package com.mars.cloud.mysql;

import com.mars.cloud.mysql.autoconfigure.MysqlRefreshAutoConfiguration;
import com.mars.cloud.mysql.autoconfigure.MysqlResilienceAutoConfiguration;
import com.mars.cloud.mysql.env.MysqlResilienceDefaults;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;

class DatabaseRefreshTest {
    @org.springframework.boot.context.properties.ConfigurationProperties("unrelated")
    public static class OtherProperties {
        private String setting="initial";
        public String getSetting() {return setting;}
        public void setSetting(String setting) {this.setting=setting;}
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withBean(OtherProperties.class).withConfiguration(AutoConfigurations.of(MysqlRefreshAutoConfiguration.class,
                MysqlResilienceAutoConfiguration.class,DataSourceAutoConfiguration.class,
                RefreshAutoConfiguration.class,ConfigurationPropertiesRebinderAutoConfiguration.class))
                .withInitializer(ctx->new MysqlResilienceDefaults().postProcessEnvironment(ctx.getEnvironment(),new SpringApplication()))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:refresh;DB_CLOSE_DELAY=-1",
                        "spring.cloud.refresh.never-refreshable=example.CustomProperties");
    }
    @Test void keepsOriginalPoolAndConnectionsThroughLegalUnrelatedAndRejectedRefreshes() {
        runner().run(ctx->{
            assertThat(ctx).hasNotFailed();
            DataSource source=ctx.getBean(DataSource.class);
            HikariDataSource pool=source.unwrap(HikariDataSource.class);
            assertThat(ctx.getBean(ConfigurationPropertiesRebinder.class).getNeverRefreshable()).contains("example.CustomProperties");
            try (var connection=source.getConnection()) {
                for (var update : java.util.List.of(Map.<String,Object>of("unrelated.setting","changed"),
                        Map.<String,Object>of("spring.datasource.hikari.maximum-pool-size","2"))) {
                    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("change",update));
                    ctx.publishEvent(new EnvironmentChangeEvent(ctx.getSourceApplicationContext(),update.keySet()));
                    assertThat(ctx.getBean(DataSource.class)).isSameAs(source);
                    assertThat(ctx.getBean(OtherProperties.class).getSetting()).isEqualTo(update.containsKey("unrelated.setting") ? "changed" : "initial");
                    assertThat(ctx.getBean(ConfigurationPropertiesRebinder.class).getErrors()).isEmpty();
                    assertThat(pool.isClosed()).isFalse();
                    assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
                    assertThat(connection.isValid(1)).isTrue();
                    try(var next=source.getConnection()) { assertThat(next.isValid(1)).isTrue(); }
                }
                ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("change",Map.of("spring.datasource.hikari.maximum-pool-size","11")));
                assertThatThrownBy(()->ctx.publishEvent(new EnvironmentChangeEvent(ctx.getSourceApplicationContext(),Set.of("spring.datasource.hikari.maximum-pool-size"))))
                        .hasMessageContaining("maximum-pool-size");
                assertThat(pool.isClosed()).isFalse(); assertThat(connection.isValid(1)).isTrue();
                assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
            }
        });
    }
    @Test void noDatasourceDoesNotApplyPoolValidationDuringRefresh() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MysqlResilienceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.hikari.maximum-pool-size=200").run(ctx->{
                    assertThat(ctx).hasNotFailed();
                    ctx.publishEvent(new EnvironmentChangeEvent(ctx.getSourceApplicationContext(),Set.of("unrelated")));
                });
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class CustomRebinder {
        @org.springframework.context.annotation.Bean
        ConfigurationPropertiesRebinder custom(org.springframework.cloud.context.properties.ConfigurationPropertiesBeans beans) {
            return new ConfigurationPropertiesRebinder(beans);
        }
    }
    @Test void rejectsCustomRebinderThatCanDestroyObservedPools() {
        runner().withUserConfiguration(CustomRebinder.class).run(ctx->assertThat(ctx).hasFailed());
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class BoundDelegate {
        @org.springframework.context.annotation.Bean
        DataSource pool() {
            HikariDataSource pool=new HikariDataSource();pool.setJdbcUrl("jdbc:h2:mem:delegate");
            pool.setMaximumPoolSize(2);pool.setMinimumIdle(0);pool.setConnectionTimeout(1000);pool.setValidationTimeout(500);return pool;
        }
        @org.springframework.context.annotation.Bean
        @org.springframework.boot.context.properties.ConfigurationProperties("delegate")
        DataSource delegate(@org.springframework.beans.factory.annotation.Qualifier("pool") DataSource pool) {
            return new org.springframework.jdbc.datasource.DelegatingDataSource(pool);
        }
    }
    @Test void preservesConfigurationBoundDelegateTargetDuringActualRefresh() {
        runner().withUserConfiguration(BoundDelegate.class).run(ctx->{
            assertThat(ctx).hasNotFailed();
            var delegate=(org.springframework.jdbc.datasource.DelegatingDataSource)ctx.getBean("delegate");
            DataSource original=delegate.getTargetDataSource();
            ctx.publishEvent(new EnvironmentChangeEvent(ctx.getSourceApplicationContext(),Set.of("unrelated.setting")));
            assertThat(delegate.getTargetDataSource()).isSameAs(original);
            try(var connection=delegate.getConnection()) {assertThat(connection.isValid(1)).isTrue();}
        });
    }

}
