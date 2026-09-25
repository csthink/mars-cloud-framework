package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.transport.HeartbeatSender;

/**
 * 不发送心跳的实现：本项目不装 Sentinel Dashboard，没有心跳的接收方。
 *
 * <p>Sentinel 的初始化函数找到心跳实现后按它给出的间隔定时调用；间隔取最大值，定时任务实际不会重复执行。
 *
 * @since 2026-09-25
 */
@Spi(order = Spi.ORDER_HIGHEST)
public final class DisabledHeartbeatSender implements HeartbeatSender {

    @Override
    public boolean sendHeartbeat() {
        return true;
    }

    @Override
    public long intervalMs() {
        return Long.MAX_VALUE;
    }
}
