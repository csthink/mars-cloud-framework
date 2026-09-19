package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.env.EnvProfilesProperties;
import com.mars.cloud.mvc.util.AppContextHolder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * @since 2025-10-30 10:55
 */
@AutoConfiguration
@EnableConfigurationProperties({
        EnvProfilesProperties.class
})
public class ContextHolderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AppContextHolder.class) // 允许业务侧自定义覆盖
    public AppContextHolder appContextHolder(Environment env, EnvProfilesProperties envProps) {
        return new AppContextHolder(env, envProps);
    }
}
