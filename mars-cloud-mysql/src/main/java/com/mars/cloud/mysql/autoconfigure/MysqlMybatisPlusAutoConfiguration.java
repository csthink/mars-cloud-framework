package com.mars.cloud.mysql.autoconfigure;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.mars.cloud.core.api.IdGenerator;
import com.mars.cloud.mysql.env.DataSourceDialectProperties;
import com.mars.cloud.mysql.mp.DefaultAuditMetaObjectHandler;
import com.mars.cloud.mysql.mp.SnowflakeIdentifierGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 约定装配：分页、乐观锁、ID 生成、审计填充。
 *
 * @since 2025-10-31 13:40
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.mars.cloud.core.autoconfigure.IdAutoConfiguration")
@EnableConfigurationProperties(DataSourceDialectProperties.class)
public class MysqlMybatisPlusAutoConfiguration {

    /**
     * 分页 + 乐观锁拦截器。
     *
     * <p>方言取法见 {@link #buildPaginationInnerInterceptor(DataSourceDialectProperties)}。
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataSourceDialectProperties props) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(buildPaginationInnerInterceptor(props));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /**
     * 构造分页拦截器。
     *
     * <p>两种模式：
     * <ul>
     *   <li>{@code mars.datasource.dialect-auto-detect=true}（默认）：交给 MyBatis-Plus
     *       依据 JDBC URL 识别（{@code JdbcUtils.getDbType}）</li>
     *   <li>{@code =false} + {@code mars.datasource.db-type=<dbtype>}：显式指定，
     *       用于自动识别认不出的数据库</li>
     * </ul>
     *
     * <p>关闭自动识别却没给方言时**直接启动失败**，而不是静默回落——方言错了要到运行时
     * 出现分页 SQL 报错才暴露，代价比启动失败大得多。
     */
    private PaginationInnerInterceptor buildPaginationInnerInterceptor(DataSourceDialectProperties props) {
        PaginationInnerInterceptor pagination;

        if (props.isDialectAutoDetect()) {
            pagination = new PaginationInnerInterceptor();
        } else {
            if (props.getDbType() == null) {
                throw new IllegalStateException(
                        "mars.datasource.dialect-auto-detect=false 时必须同时指定 mars.datasource.db-type，"
                                + "例如 dm / kingbase_es / gauss / oscar");
            }
            pagination = new PaginationInnerInterceptor(props.getDbType());
        }

        pagination.setMaxLimit(props.getMaxLimit());
        pagination.setOverflow(props.isOverflow());
        pagination.setOptimizeJoin(props.isOptimizeJoin());
        return pagination;
    }

    /**
     * 统一 ID 生成（接入 Snowflake）
     */
    @Bean
    @ConditionalOnBean(IdGenerator.class)
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator identifierGeneratorWithCore(IdGenerator idGenerator) {
        return new SnowflakeIdentifierGenerator(idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean(IdentifierGenerator.class)
    public IdentifierGenerator identifierGeneratorFallback() {
        return new DefaultIdentifierGenerator();
    }

    /**
     * 审计自动填充（默认使用 system，业务可自定义 AuditorProvider 覆盖）
     */
    @Bean
    @ConditionalOnMissingBean(DefaultAuditMetaObjectHandler.class)
    public DefaultAuditMetaObjectHandler metaObjectHandler() {
        return new DefaultAuditMetaObjectHandler(new DefaultAuditMetaObjectHandler.SystemAuditorProvider());
    }
}
