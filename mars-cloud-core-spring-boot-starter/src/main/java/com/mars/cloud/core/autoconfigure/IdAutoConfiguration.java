package com.mars.cloud.core.autoconfigure;

import cn.hutool.core.lang.Snowflake;
import com.mars.cloud.core.api.IdGenerator;
import com.mars.cloud.core.id.snowflake.SnowflakeIdGenerator;
import com.mars.cloud.core.id.snowflake.SnowflakeProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * @since 2025-10-30 15:39
 */

@AutoConfiguration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class IdAutoConfiguration {

    /**
     * 创建 Hutool Snowflake 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "mars.id.snowflake", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(Snowflake.class)
    public Snowflake snowflake(SnowflakeProperties props) {
        return SnowflakeIdGenerator.build(props.getWorkerId(), props.getDataCenterId());
    }

    /**
     * 暴露统一的 IdGenerator 接口供上层使用
     */
    @Bean
    @ConditionalOnBean(Snowflake.class)
    @ConditionalOnMissingBean(IdGenerator.class)
    public IdGenerator idGenerator(Snowflake snowflake) {
        return new SnowflakeIdGenerator(snowflake);
    }
}
