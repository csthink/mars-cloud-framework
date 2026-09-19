package com.mars.cloud.mysql;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectFactory;
import com.mars.cloud.mysql.autoconfigure.MysqlMybatisPlusAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源方言可配置性的契约测试。
 *
 * <p>国产数据库（达梦、人大金仓等）的方言要能显式指定，且配置错误必须在**启动期**失败，
 * 而不是等到运行时生成分页 SQL 才炸。
 */
class DataSourceDialectTest {

    /**
     * 用独立的配置类，避免污染 {@link MysqlTestApplication.App}（它固定用自动识别 + H2）。
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MysqlMybatisPlusAutoConfiguration.class)
    static class DialectTestConfig {
    }

    /**
     * 自定义拦截器：验证「业务侧声明自己的拦截器时，默认的不再创建」。
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MysqlMybatisPlusAutoConfiguration.class)
    static class CustomInterceptorConfig {
        @Bean
        MybatisPlusInterceptor mybatisPlusInterceptor() {
            return new MybatisPlusInterceptor();
        }
    }

    private static PaginationInnerInterceptor paginationOf(MybatisPlusInterceptor interceptor) {
        return (PaginationInnerInterceptor) interceptor.getInterceptors().stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 反射读私有字段 {@code dbType}：MyBatis-Plus 没有对外暴露 getter，
     * 只能这样断言「显式指定的方言真的被用上了」。
     */
    private static Object dbTypeOf(PaginationInnerInterceptor pagination) throws Exception {
        Field field = PaginationInnerInterceptor.class.getDeclaredField("dbType");
        field.setAccessible(true);
        return field.get(pagination);
    }

    @Nested
    @DisplayName("显式指定方言")
    class ExplicitDialect {

        @Test
        @DisplayName("达梦（dm）：被接受，且归入 Oracle 方言家族")
        void dm_isAcceptedAndUsesOracleDialect() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.dialect-auto-detect=false",
                            "mars.datasource.db-type=dm")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        PaginationInnerInterceptor pagination =
                                paginationOf(context.getBean(MybatisPlusInterceptor.class));
                        assertThat(dbTypeOf(pagination)).isEqualTo(DbType.DM);
                        // 达梦没有独立分页方言实现，MyBatis-Plus 把 DM 归入 Oracle 家族
                        assertThat(DialectFactory.getDialect(DbType.DM).getClass().getSimpleName())
                                .isEqualTo("OracleDialect");
                    });
        }

        @Test
        @DisplayName("人大金仓（kingbase_es）：被接受")
        void kingbaseEs_isAccepted() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.dialect-auto-detect=false",
                            "mars.datasource.db-type=kingbase_es")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(dbTypeOf(paginationOf(context.getBean(MybatisPlusInterceptor.class))))
                                .isEqualTo(DbType.KINGBASE_ES);
                    });
        }

        @Test
        @DisplayName("枚举名大小写不敏感（DM 与 dm 等价）")
        void dbTypeIsCaseInsensitive() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.dialect-auto-detect=false",
                            "mars.datasource.db-type=MySQL")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(dbTypeOf(paginationOf(context.getBean(MybatisPlusInterceptor.class))))
                                .isEqualTo(DbType.MYSQL);
                    });
        }

        @Test
        @DisplayName("非法方言名 → 启动失败（不静默回落）")
        void unknownDbType_failsFast() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.dialect-auto-detect=false",
                            "mars.datasource.db-type=not-a-database")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Nested
    @DisplayName("默认：自动识别")
    class AutoDetect {

        @Test
        @DisplayName("不配置时默认识别，不绑定具体方言")
        void defaultKeepsAutoDetect() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(dbTypeOf(paginationOf(context.getBean(MybatisPlusInterceptor.class))))
                                .isNull();
                    });
        }

        @Test
        @DisplayName("关闭自动识别但未给方言 → 启动失败，而不是静默回落")
        void autoDetectOffWithoutDbType_failsFast() {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.dialect-auto-detect=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("必须同时指定 mars.datasource.db-type");
                    });
        }

        @Test
        @DisplayName("分页参数可配：max-limit / overflow / optimize-join")
        void paginationOptionsAreApplied() throws Exception {
            new ApplicationContextRunner()
                    .withUserConfiguration(DialectTestConfig.class)
                    .withPropertyValues("mars.datasource.max-limit=500",
                            "mars.datasource.overflow=true",
                            "mars.datasource.optimize-join=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        PaginationInnerInterceptor pagination =
                                paginationOf(context.getBean(MybatisPlusInterceptor.class));

                        assertThat(fieldOf(pagination, "maxLimit")).isEqualTo(500L);
                        assertThat(fieldOf(pagination, "overflow")).isEqualTo(true);
                        assertThat(fieldOf(pagination, "optimizeJoin")).isEqualTo(false);
                    });
        }
    }

    @Nested
    @DisplayName("业务侧覆盖")
    class Override {

        @Test
        @DisplayName("业务侧自定义 MybatisPlusInterceptor 后，默认的不再创建")
        void customInterceptorWins() {
            new ApplicationContextRunner()
                    .withUserConfiguration(CustomInterceptorConfig.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
                        assertThat(interceptor.getInterceptors()).isEmpty();
                    });
        }
    }

    private static Object fieldOf(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
