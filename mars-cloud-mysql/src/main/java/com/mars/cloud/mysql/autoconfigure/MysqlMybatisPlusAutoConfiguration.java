package com.mars.cloud.mysql.autoconfigure;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.mars.cloud.core.api.IdGenerator;
import com.mars.cloud.mysql.mp.DefaultAuditMetaObjectHandler;
import com.mars.cloud.mysql.mp.SnowflakeIdentifierGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * @since 2025-10-31 13:40
 */
@AutoConfiguration
@AutoConfigureAfter(name = "com.mars.cloud.core.autoconfigure.IdAutoConfiguration")
public class MysqlMybatisPlusAutoConfiguration {

    /**
     * 分页 + 乐观锁拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        // 乐观锁
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
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
