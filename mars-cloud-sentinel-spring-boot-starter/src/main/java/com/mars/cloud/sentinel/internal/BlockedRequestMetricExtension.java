package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.metric.extension.MetricExtension;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * Sentinel 的指标扩展，只处理拦截事件，转给当前的 {@link BlockedRequestRecorder}。
 *
 * <p>Sentinel 按 SPI 用无参构造器创建这个类，所以记录方式经静态字段设置；类本身不引用 Micrometer，
 * 没有 Micrometer 的应用也能加载它。去掉 {@code LogSlot} 后，拦截事件只从这里记录，不写文件。
 *
 * @since 2026-09-25
 */
public final class BlockedRequestMetricExtension implements MetricExtension {

    private static volatile BlockedRequestRecorder recorder;

    public static void bind(BlockedRequestRecorder value) {
        recorder = value;
    }

    public static void unbind(BlockedRequestRecorder value) {
        if (recorder == value) {
            recorder = null;
        }
    }

    @Override
    public void addBlock(String resource, int n, String origin, BlockException blockException, Object... args) {
        BlockedRequestRecorder current = recorder;
        if (current != null) {
            current.blocked(resource, blockException, n);
        }
    }

    @Override
    public void addPass(String resource, int n, Object... args) {
    }

    @Override
    public void addSuccess(String resource, int n, Object... args) {
    }

    @Override
    public void addException(String resource, int n, Throwable throwable) {
    }

    @Override
    public void addRt(String resource, long rt, Object... args) {
    }

    @Override
    public void increaseThreadNum(String resource, Object... args) {
    }

    @Override
    public void decreaseThreadNum(String resource, Object... args) {
    }
}
