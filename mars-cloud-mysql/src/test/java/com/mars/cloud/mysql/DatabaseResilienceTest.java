package com.mars.cloud.mysql;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mars.cloud.mysql.autoconfigure.MysqlResilienceAutoConfiguration;
import com.mars.cloud.mysql.env.MysqlResilienceDefaults;
import com.mars.cloud.mysql.resilience.MysqlResilienceLimits;
import com.mars.cloud.mysql.resilience.ObservedDataSource;
import com.mars.cloud.mysql.resilience.SlowQueryListener;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DatabaseResilienceTest {
    private HikariDataSource pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(300);
        pool.setValidationTimeout(250);
        return pool;
    }

    @ParameterizedTest
    @ValueSource(strings = {"maximum-pool-size=11", "maximum-pool-size=0", "minimum-idle=11",
            "connection-timeout=1001", "connection-timeout=250", "validation-timeout=249", "validation-timeout=501"})
    void rejectsRelaxedOrInvalidProperties(String property) {
        String[] pair = property.split("=");
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.hikari." + pair[0], pair[1]);
        assertThatThrownBy(() -> MysqlResilienceLimits.validateEnvironment(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresValidationTimeoutBelowConnectionTimeout() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.hikari.connection-timeout", "300")
                .withProperty("spring.datasource.hikari.validation-timeout", "300");
        assertThatThrownBy(() -> MysqlResilienceLimits.validateEnvironment(env)).hasMessageContaining("less than");
    }

    @Test
    void defaultsRemainLowPriorityAndSlowThresholdCannotBeDisabled() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.hikari.maximum-pool-size", "2");
        new MysqlResilienceDefaults().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("2");
        assertThat(MysqlResilienceLimits.validateEnvironment(env)).isEqualTo(Duration.ofSeconds(1));
        for (String invalid : List.of("0", "-1s", "2s", "bad")) {
            env.setProperty("mars.datasource.slow-query-threshold", invalid);
            assertThatThrownBy(() -> MysqlResilienceLimits.validateEnvironment(env)).hasMessageContaining("slow-query-threshold");
        }
    }

    @Test
    void checksCustomPoolAndRejectsUnsupportedDatasource() {
        try (HikariDataSource pool = pool()) {
            pool.setMaximumPoolSize(11);
            assertThatThrownBy(() -> MysqlResilienceLimits.validateDataSource(pool)).hasMessageContaining("maximum-pool-size");
        }
        assertThatThrownBy(() -> MysqlResilienceLimits.validateDataSource(new DriverManagerDataSource()))
                .hasMessageContaining("unwrap");
    }

    @Test
    void decoratesCustomBeansWithoutReplacingOrDuplicatingThePool() {
        HikariDataSource original = pool();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MysqlResilienceAutoConfiguration.class))
                .withBean("dataSource", DataSource.class, () -> original).run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource observed = context.getBean(DataSource.class);
                    assertThat(observed).isInstanceOf(ObservedDataSource.class);
                    assertThat(observed.unwrap(HikariDataSource.class)).isSameAs(original);
                    assertThat(observed.isWrapperFor(HikariDataSource.class)).isTrue();
                });
        assertThat(original.isClosed()).isTrue();
    }

    @Test
    void preservesGeneratedKeysBatchRollbackAndPoolExhaustionRecovery() throws Exception {
        HikariDataSource pool = pool();
        try (ObservedDataSource observed = new ObservedDataSource(pool, new SlowQueryListener("orders", Duration.ofSeconds(1)))) {
            try (var connection = observed.getConnection(); var statement = connection.createStatement()) {
                statement.execute("create table items(id bigint generated by default as identity primary key, name varchar(80))");
                connection.setAutoCommit(false);
                try (var insert = connection.prepareStatement("insert into items(name) values(?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, "private-value");
                    insert.executeUpdate();
                    try (var keys = insert.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); }
                    insert.setString(1, "first"); insert.addBatch();
                    insert.setString(1, "second"); insert.addBatch();
                    assertThat(insert.executeBatch()).hasSize(2);
                }
                connection.rollback();
                try (var rows = statement.executeQuery("select count(*) from items")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
                }
                long start = System.nanoTime();
                assertThatThrownBy(observed::getConnection).isInstanceOf(SQLException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(200L, 1500L);
            }
            try (var recovered = observed.getConnection()) { assertThat(recovered.isValid(1)).isTrue(); }
        }
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void logsOnlyFixedFieldsAndFingerprintIncludingFailuresAndBatch() {
        Logger logger = (Logger) LoggerFactory.getLogger(SlowQueryListener.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); }
        };
        events.start(); logger.addAppender(events); boolean additive = logger.isAdditive(); logger.setAdditive(false);
        try {
            MDC.put("traceId", "trace-for-query");
            SlowQueryListener listener = new SlowQueryListener("orders", Duration.ofMillis(100));
            ExecutionInfo execution = new ExecutionInfo();
            execution.setStatementType(StatementType.PREPARED);
            execution.setSuccess(false);
            execution.setThrowable(new SQLException("exception-sensitive-marker"));
            execution.setMethodArgs(new Object[] {"parameter-sensitive-marker"});
            execution.setElapsedTime(99);
            List<QueryInfo> queries = List.of(new QueryInfo("select 'sql-sensitive-marker'"));
            listener.afterQuery(execution, queries); assertThat(events.list).isEmpty();
            execution.setElapsedTime(100); execution.setBatch(true); execution.setBatchSize(4);
            listener.afterQuery(execution, queries);
            assertThat(events.list).hasSize(1);
            ILoggingEvent event = events.list.getFirst();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Slow JDBC execution");
            assertThat(event.getKeyValuePairs().toString()).contains("jdbc.slow", "orders", "100", "false")
                    .doesNotContain("sql-sensitive-marker", "parameter-sensitive-marker", "exception-sensitive-marker");
            assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                assertThat(pair.key).isEqualTo("sql.fingerprint");
                assertThat(pair.value.toString()).matches("[0-9a-f]{64}");
            });
            assertThat(event.getMDCPropertyMap()).containsEntry("traceId", "trace-for-query");
        }
        finally { MDC.remove("traceId"); logger.detachAppender(events); logger.setAdditive(additive); events.stop(); }
    }
}
