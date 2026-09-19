package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.error.ExceptionCodeConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.PropertySource;

/**
 * @since 2025-10-30 10:49
 */
@AutoConfiguration
@EnableConfigurationProperties(ExceptionCodeConfiguration.class)
@PropertySource(value="classpath:config/exception-code.properties",
        ignoreResourceNotFound = true, encoding = "UTF-8")
public class ExceptionCodeAutoConfiguration {
    // 无需写 @Bean，EnableConfigurationProperties 会把 ExceptionCodeConfiguration 注册为 Bean
}
