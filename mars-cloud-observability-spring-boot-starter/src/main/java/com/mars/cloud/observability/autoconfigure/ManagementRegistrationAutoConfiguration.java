package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.registry.ManagementPortRegistrationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 把管理端口写进服务实例元数据；部署物没有接入注册中心时不装配。 */
@AutoConfiguration(after = MarsObservabilityAutoConfiguration.class)
@ConditionalOnClass(name = "com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer")
public class ManagementRegistrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ManagementPortRegistrationCustomizer marsManagementPortRegistrationCustomizer(Environment environment) {
        return new ManagementPortRegistrationCustomizer(environment);
    }
}
