package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.transport.CommandCenter;

/**
 * 不监听任何端口的命令中心。
 *
 * <p>Sentinel 自带的 HTTP 命令中心开放一个没有认证、能在运行期改规则的端口，本组件已把它排除，
 * 规则只从 Nacos 进入。{@code sentinel-transport-common} 的初始化函数找不到命令中心实现时会告警，
 * 这个实现让初始化照常完成，日志里的实现类名如实说明命令中心未启用。
 *
 * @since 2026-09-25
 */
@Spi(order = Spi.ORDER_HIGHEST)
public final class DisabledCommandCenter implements CommandCenter {

    @Override
    public void beforeStart() {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
