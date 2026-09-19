package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 注册 mars-cloud 的 Nacos 约定校验。
 */
@AutoConfiguration
@EnableConfigurationProperties(NacosConventionProperties.class)
public class NacosConventionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    NacosConventionVerifier nacosConventionVerifier(
            Environment environment,
            NacosConventionProperties properties) {
        return new NacosConventionVerifier(environment, properties);
    }
}
