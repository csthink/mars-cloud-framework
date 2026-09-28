package com.mars.cloud.nacos.autoconfigure;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import com.mars.cloud.nacos.registration.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName={"com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration",
        "com.mars.cloud.nacos.autoconfigure.NacosDiscoveryLifecycleAutoConfiguration",
        "org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration"})
@ConditionalOnWebApplication
@ConditionalOnProperty(name={"spring.cloud.nacos.discovery.enabled", "spring.cloud.discovery.enabled",
        "spring.cloud.service-registry.auto-registration.enabled", "spring.cloud.nacos.discovery.register-enabled"}, matchIfMissing=true)
public class NacosRegistrationAutoConfiguration {
    @Bean
    NacosRegistration nacosRegistration(ObjectProvider<NacosRegistrationCustomizer> customizers,
            NacosDiscoveryProperties properties,ApplicationContext context) {
        return new NacosRegistration(customizers.orderedStream().toList(),properties,context);
    }
    @Bean
    NacosReadinessEvaluator nacosReadinessEvaluator(HealthEndpoint endpoint,HealthEndpointGroups groups,
            ObjectProvider<HealthContributorRegistry> blocking,ObjectProvider<ReactiveHealthContributorRegistry> reactive,
            ApplicationAvailability availability) {
        return new NacosReadinessEvaluator(endpoint,groups,blocking.getIfAvailable(),reactive.getIfAvailable(),availability);
    }
    @Bean
    NacosRegistrationLifecycle nacosRegistrationLifecycle(ApplicationContext context,NacosRegistration registration,
            @org.springframework.beans.factory.annotation.Qualifier("nacosDiscoveryClientOwner") org.springframework.beans.factory.DisposableBean discoveryOwner,
            ObjectProvider<NacosRegistrationCustomizer> customizers,NacosReadinessEvaluator readiness,NacosServiceManager discovery) {
        return new NacosRegistrationLifecycle(context,new RegistrationConfiguration(context,customizers.orderedStream().toList(),
                registration.getHost()),readiness,discovery);
    }
    @Bean
    static BeanPostProcessor rejectAlternativeRegistration() {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean,String name) {
                if (bean instanceof ServiceRegistry<?> || (bean instanceof AutoServiceRegistration && !(bean instanceof NacosRegistrationLifecycle)))
                    throw new IllegalStateException("Nacos registration requires the readiness-controlled registration lifecycle");
                return bean;
            }
        };
    }
}
