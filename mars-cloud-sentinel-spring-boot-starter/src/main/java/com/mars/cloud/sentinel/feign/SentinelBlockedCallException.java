package com.mars.cloud.sentinel.feign;

import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.io.IOException;

/**
 * 对下游的调用被 Sentinel 拦截，请求没有发出。
 *
 * <p>它是传输层的失败：feign 组件把它映射为「下游不可用」交给调用方注册的映射器，调用方已有的处理直接生效；
 * 需要区分时看映射器收到的失败原因。
 *
 * @since 2026-09-25
 */
public final class SentinelBlockedCallException extends IOException {

    private final String resource;

    public SentinelBlockedCallException(String resource, BlockException cause) {
        super("对下游的调用被 Sentinel 拦截：" + resource + "（" + cause.getClass().getSimpleName() + "）", cause);
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }

    @Override
    public synchronized BlockException getCause() {
        return (BlockException) super.getCause();
    }
}
