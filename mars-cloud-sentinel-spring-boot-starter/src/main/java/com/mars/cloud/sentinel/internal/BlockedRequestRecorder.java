package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 被拦截请求的记录方式。由 Spring 装配在启动时设置到 {@link BlockedRequestMetricExtension}。
 *
 * @since 2026-09-25
 */
public interface BlockedRequestRecorder {

    void blocked(String resource, BlockException exception, int count);
}
