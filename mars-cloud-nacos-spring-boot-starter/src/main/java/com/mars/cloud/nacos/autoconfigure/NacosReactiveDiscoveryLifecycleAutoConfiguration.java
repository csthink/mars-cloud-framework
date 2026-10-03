package com.mars.cloud.nacos.autoconfigure;

import com.alibaba.cloud.nacos.ConditionalOnNacosDiscoveryEnabled;
import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient;
import com.mars.cloud.nacos.discovery.CancellationSafeNacosReactiveDiscoveryClient;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.ConditionalOnDiscoveryEnabled;
import org.springframework.cloud.client.ConditionalOnReactiveDiscoveryEnabled;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;

/** Owns in-progress reactive discovery calls before the shared Nacos client is released. */
@AutoConfiguration(after = NacosDiscoveryLifecycleAutoConfiguration.class,
        beforeName = "com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClientConfiguration")
@ConditionalOnClass(Flux.class)
@ConditionalOnDiscoveryEnabled
@ConditionalOnReactiveDiscoveryEnabled
@ConditionalOnNacosDiscoveryEnabled
public class NacosReactiveDiscoveryLifecycleAutoConfiguration {
    @Bean
    @DependsOn("nacosDiscoveryClientOwner")
    @ConditionalOnMissingBean(NacosReactiveDiscoveryClient.class)
    CancellationSafeNacosReactiveDiscoveryClient nacosReactiveDiscoveryClient(NacosServiceDiscovery discovery, Environment environment, AutowireCapableBeanFactory beanFactory) {
        return new CancellationSafeNacosReactiveDiscoveryClient(discovery,
                DurationStyle.detectAndParse(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", "30s")), beanFactory::autowireBean);
    }
}
