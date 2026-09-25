package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 不写响应，把拦截异常交回 WebFlux 的异常处理链，由应用自己的错误处理写统一格式的响应。
 *
 * <p>应用没有映射拦截异常时，请求得到应用的兜底错误响应，不会出现 Sentinel 自带的文本。
 *
 * @since 2026-09-25
 */
public final class ForwardingBlockRequestHandler implements BlockRequestHandler {

    @Override
    public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable blocked) {
        return Mono.error(blocked);
    }
}
