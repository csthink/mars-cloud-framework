package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关用例的应用：一个转发目标、一个模拟入站过滤器（从请求头取客户端地址写入交换属性）
 * 与一个模拟统一错误处理（把拦截异常写成 429）。规则来自内存。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class GatewayProbeApplication {

    static final String CLIENT_IP_ATTRIBUTE = "probe.clientIp";
    static final String CLIENT_IP_HEADER = "X-Probe-Client";
    static final String APPLICATION = "gateway-probe";

    static final InMemoryRuleConfigSource RULES = new InMemoryRuleConfigSource();

    @Bean
    RuleConfigSource ruleConfigSource() {
        return RULES;
    }

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    RouterFunction<ServerResponse> stub() {
        return RouterFunctions.route()
                .GET("/stub/**", request -> ServerResponse.ok().bodyValue("ok"))
                .build();
    }

    /** 代码定义的路由：规则按路由 ID 引用它时也要通过校验。 */
    @Bean
    RouteLocator codeDefinedRoutes(RouteLocatorBuilder builder) {
        return builder.routes().route("code-defined", route -> route.path("/code/**").uri("forward:/stub")).build();
    }

    @Bean
    WebFilter ingress() {
        return new Ingress();
    }

    @Bean
    @Order(-2)
    WebExceptionHandler applicationErrors() {
        return (exchange, ex) -> {
            boolean blocked = BlockException.isBlockException(ex);
            exchange.getResponse().setStatusCode(blocked ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.INTERNAL_SERVER_ERROR);
            String body = blocked ? "blocked:" + ex.getClass().getSimpleName() : "error:" + ex.getMessage();
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8))));
        };
    }

    static final class Ingress implements WebFilter, Ordered {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String client = exchange.getRequest().getHeaders().getFirst(CLIENT_IP_HEADER);
            if (client != null) {
                exchange.getAttributes().put(CLIENT_IP_ATTRIBUTE, client);
            }
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
