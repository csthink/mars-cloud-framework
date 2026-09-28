package com.mars.cloud.mysql;

import com.mars.cloud.mysql.autoconfigure.MysqlResilienceAutoConfiguration;
import com.mars.cloud.mysql.env.MysqlResilienceDefaults;
import com.mars.cloud.mysql.resilience.ObservedDataSource;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import static org.assertj.core.api.Assertions.*;

class DatabaseAutoConfigurationTest {
    @Test void migrationHealthMetricsAndTransactionManagerUseTheSameObservedPool() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MysqlResilienceAutoConfiguration.class,
                DataSourceAutoConfiguration.class,DataSourceTransactionManagerAutoConfiguration.class,JdbcTemplateAutoConfiguration.class,
                DataSourceHealthContributorAutoConfiguration.class,DataSourcePoolMetricsAutoConfiguration.class,
                FlywayAutoConfiguration.class,MetricsAutoConfiguration.class,SimpleMetricsExportAutoConfiguration.class))
                .withInitializer(ctx->new MysqlResilienceDefaults().postProcessEnvironment(ctx.getEnvironment(),new SpringApplication()))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:observation;DB_CLOSE_DELAY=-1",
                        "spring.datasource.hikari.pool-name=observed-pool","spring.flyway.locations=classpath:observation-migrations")
                .run(ctx->{
                    assertThat(ctx).hasNotFailed();assertThat(ctx).hasSingleBean(DataSource.class);
                    DataSource source=ctx.getBean(DataSource.class);assertThat(source).isInstanceOf(ObservedDataSource.class);
                    assertThat(ctx.getBean(Flyway.class).getConfiguration().getDataSource()).isSameAs(source);
                    JdbcTemplate jdbc=ctx.getBean(JdbcTemplate.class);assertThat(jdbc.getDataSource()).isSameAs(source);
                    assertThat(jdbc.queryForObject("select count(*) from observed_records",Integer.class)).isZero();
                    new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).executeWithoutResult(status->{
                        jdbc.update("insert into observed_records values(1,'private-value')");status.setRollbackOnly();
                    });
                    assertThat(jdbc.queryForObject("select count(*) from observed_records",Integer.class)).isZero();
                    assertThat(((HealthIndicator)ctx.getBean("dbHealthContributor")).health().getStatus()).isEqualTo(Status.UP);
                    HikariDataSource pool=source.unwrap(HikariDataSource.class);assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
                    MeterRegistry metrics=ctx.getBean(MeterRegistry.class);
                    assertThat(metrics.get("jdbc.connections.max").gauge().value()).isEqualTo(10);
                    assertThat(metrics.get("hikaricp.connections.max").tag("pool","observed-pool").gauge().value()).isEqualTo(10);
                });
    }
}
