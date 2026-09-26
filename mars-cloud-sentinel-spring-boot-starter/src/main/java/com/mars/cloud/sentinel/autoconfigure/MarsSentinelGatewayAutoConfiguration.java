package com.mars.cloud.sentinel.autoconfigure;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.mars.cloud.sentinel.gateway.ClientIpAttributeItemParser;
import com.mars.cloud.sentinel.gateway.ForwardingBlockRequestHandler;
import com.mars.cloud.sentinel.gateway.GatewayRuleKinds;
import com.mars.cloud.sentinel.rule.SentinelRuleCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 网关部署物：Sentinel 网关过滤器按交换属性里的客户端地址计数，拦截异常交回应用的错误处理，
 * 订阅 {@code gw-api-group} 与 {@code gw-flow} 两种规则。
 *
 * <p>排在 Spring Cloud Alibaba 的网关装配之前，它的过滤器与拦截回调都以本类的 bean 为准。
 *
 * @since 2026-09-25
 */
@AutoConfiguration(before = MarsSentinelAutoConfiguration.class,
        beforeName = "com.alibaba.cloud.sentinel.gateway.scg.SentinelSCGAutoConfiguration")
@ConditionalOnClass({SentinelGatewayFilter.class, GlobalFilter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", matchIfMissing = true)
@EnableConfigurationProperties(MarsSentinelProperties.class)
public class MarsSentinelGatewayAutoConfiguration {

    /**
     * Sentinel 网关过滤器的顺序：在网关自己以 {@link Ordered#HIGHEST_PRECEDENCE} 登记的全局过滤器（例如路由暴露检查）
     * 之后，在 Spring Cloud Gateway 的路由地址过滤器（{@code RouteToRequestUrlFilter}，10000）与负载均衡过滤器
     * （{@code ReactiveLoadBalancerClientFilter}，10150）之前。与网关的检查同为最高优先级时先后只取决于登记顺序，
     * 不该暴露的路由可能先被计数、先返回 429。
     */
    public static final int GATEWAY_FILTER_ORDER = -1000;

    private static final Duration ROUTE_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    SentinelGatewayFilter sentinelGatewayFilter(MarsSentinelProperties properties) {
        String attribute = properties.getGateway().getClientIpAttribute();
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalStateException("网关部署物必须配置 mars.sentinel.gateway.client-ip-attribute："
                    + "它指定入站过滤器写入客户端地址的交换属性，按来源地址限流用它计数，不回退到 TCP 对端地址");
        }
        return new SentinelGatewayFilter(GATEWAY_FILTER_ORDER, new ClientIpAttributeItemParser(attribute));
    }

    @Bean
    BlockRequestHandler marsSentinelBlockRequestHandler() {
        return new ForwardingBlockRequestHandler();
    }

    /**
     * 路由 ID 取自 {@link RouteLocator} 的全部路由：配置文件声明的与代码定义的都在内，
     * 与 Sentinel 网关过滤器计数时用的路由 ID 同源。每次校验时读取，路由刷新后按新值核对。
     */
    @Bean
    SentinelRuleCatalog marsSentinelGatewayRuleCatalog(ObjectProvider<RouteLocator> routeLocator) {
        GatewayRuleKinds kinds = new GatewayRuleKinds(() -> routeIds(routeLocator.getObject()));
        return new SentinelRuleCatalog(kinds.all());
    }

    private static Set<String> routeIds(RouteLocator locator) {
        Set<String> ids = locator.getRoutes()
                .map(Route::getId)
                .collect(Collectors.toSet())
                .block(ROUTE_READ_TIMEOUT);
        return ids == null ? Set.of() : ids;
    }
}
