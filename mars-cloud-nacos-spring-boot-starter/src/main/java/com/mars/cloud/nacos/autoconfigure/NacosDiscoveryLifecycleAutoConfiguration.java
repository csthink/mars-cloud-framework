package com.mars.cloud.nacos.autoconfigure;

import com.alibaba.cloud.nacos.NacosServiceManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/** Releases discovery clients even when this application only consumes registrations. */
@AutoConfiguration(afterName="com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration")
@ConditionalOnBean(NacosServiceManager.class)
public class NacosDiscoveryLifecycleAutoConfiguration {
    @Bean
    DisposableBean nacosDiscoveryClientOwner(NacosServiceManager manager) { return manager::nacosServiceShutDown; }
}
