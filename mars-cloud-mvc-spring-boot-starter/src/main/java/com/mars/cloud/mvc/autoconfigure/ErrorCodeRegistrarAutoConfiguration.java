package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.env.ErrorCodeRangeProperties;
import com.mars.cloud.mvc.error.ErrorCodeRangeValidator;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * @since 2025-10-30 10:15
 */
@AutoConfiguration
@EnableConfigurationProperties(ErrorCodeRangeProperties.class)
@ConditionalOnClass(ErrorCodeRegistrar.class) // 有这个接口才生效
public class ErrorCodeRegistrarAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mars.error-code.validate", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(ErrorCodeRangeValidator.class) // 避免重复注册
    public ErrorCodeRangeValidator errorCodeRangeValidator(
            List<ErrorCodeRegistrar> registrars,
            ErrorCodeRangeProperties props) {
        return new ErrorCodeRangeValidator(registrars, props);
    }
}
