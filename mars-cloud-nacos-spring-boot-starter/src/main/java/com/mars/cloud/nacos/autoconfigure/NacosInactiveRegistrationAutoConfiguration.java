package com.mars.cloud.nacos.autoconfigure;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.context.annotation.Bean;

/** Satisfies Commons registration discovery when registration is explicitly inactive. */
@AutoConfiguration(afterName={"com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration",
        "com.mars.cloud.nacos.autoconfigure.NacosRegistrationAutoConfiguration"})
@ConditionalOnBean(NacosDiscoveryProperties.class)
public class NacosInactiveRegistrationAutoConfiguration {
    @Bean @ConditionalOnMissingBean(AutoServiceRegistration.class)
    AutoServiceRegistration inactiveNacosRegistration() {return new AutoServiceRegistration() { };}
}
