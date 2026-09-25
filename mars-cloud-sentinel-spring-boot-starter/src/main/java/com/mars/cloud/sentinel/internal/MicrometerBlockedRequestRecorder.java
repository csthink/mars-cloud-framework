package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 被拦截请求的计数器 {@value #BLOCKED}。资源名来自路由 ID、API 分组、Servlet 路径模板与 Feign 客户端名，数量有界。
 *
 * @since 2026-09-25
 */
public final class MicrometerBlockedRequestRecorder implements BlockedRequestRecorder {

    public static final String BLOCKED = "mars.sentinel.requests.blocked";

    private final MeterRegistry registry;

    public MicrometerBlockedRequestRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void blocked(String resource, BlockException exception, int count) {
        Counter.builder(BLOCKED)
                .description("被 Sentinel 拦截的请求数")
                .tag("resource", resource)
                .tag("exception", exception == null ? "unknown" : exception.getClass().getSimpleName())
                .register(registry)
                .increment(count);
    }
}
