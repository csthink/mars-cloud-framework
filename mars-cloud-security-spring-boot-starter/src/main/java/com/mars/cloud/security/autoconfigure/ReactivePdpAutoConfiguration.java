package com.mars.cloud.security.autoconfigure;

import com.mars.cloud.security.reactive.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/** The load-balanced WebClient adapter is optional and isolated from Servlet applications. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ReactorLoadBalancerExchangeFilterFunction.class)
@ConditionalOnProperty(prefix = "mars.security.authorization", name = "enabled", matchIfMissing = true)
public class ReactivePdpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ReactivePdpClient.class)
    ReactivePdpClient marsReactivePdpClient(
            org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer.Factory<org.springframework.cloud.client.ServiceInstance> factory) {
        var loadBalancer = new ReactorLoadBalancerExchangeFilterFunction(factory, java.util.List.of());
        return new WebClientPdpClient(WebClient.builder()
                .clientConnector(ReactiveSecurityAutoConfiguration.connector()).filter(loadBalancer));
    }
}
