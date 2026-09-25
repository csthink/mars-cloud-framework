package com.mars.cloud.sentinel.internal;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.DefaultProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.SlotChainBuilder;
import com.alibaba.csp.sentinel.slots.logger.LogSlot;
import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.spi.SpiLoader;

/**
 * 与 Sentinel 默认处理链相同、只去掉 {@link LogSlot} 的处理链构建器。
 *
 * <p>{@code LogSlot} 在每次拦截时把统计写进 {@code csp.sentinel.log.dir} 下的 {@code sentinel-block.log}
 * （单文件上限 300 MB、保留 3 份），首次写入时 EagleEye 还在用户目录下建自身日志，两者都没有关闭开关。
 * 拦截次数改由 {@link BlockedRequestMetricExtension} 计入指标。其余处理槽的加载方式与顺序照搬
 * {@code DefaultSlotChainBuilder}；升级 Sentinel 时由处理链比对用例核对两者只差 {@code LogSlot}。
 *
 * @since 2026-09-25
 */
@Spi
public final class LogSlotFreeSlotChainBuilder implements SlotChainBuilder {

    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = new DefaultProcessorSlotChain();
        for (ProcessorSlot<?> slot : SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted()) {
            if (slot instanceof LogSlot) {
                continue;
            }
            if (!(slot instanceof AbstractLinkedProcessorSlot<?> linked)) {
                // 与 DefaultSlotChainBuilder 的处理相同
                RecordLog.warn("The ProcessorSlot(" + slot.getClass().getCanonicalName()
                        + ") is not an instance of AbstractLinkedProcessorSlot, can't be added into ProcessorSlotChain");
                continue;
            }
            chain.addLast(linked);
        }
        return chain;
    }
}
