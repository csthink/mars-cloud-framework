package com.mars.cloud.sentinel.feign;

import feign.Capability;
import feign.Client;
import org.springframework.core.Ordered;

/**
 * 给每个 Feign 客户端加 Sentinel 资源。
 *
 * <p>Spring Cloud OpenFeign 按顺序逐个应用 {@link Capability}，先应用的包在里层。本类排在最前，
 * 所以它包住负载均衡客户端（一次逻辑调用含换实例重试只进入一次资源），又位于 feign 组件的失败映射里层：
 * 被拦截时抛出的 {@link SentinelBlockedCallException} 会被映射为「下游不可用」。
 *
 * @since 2026-09-25
 */
public final class SentinelFeignCapability implements Capability, Ordered {

    @Override
    public Client enrich(Client client) {
        return new SentinelFeignClient(client);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
