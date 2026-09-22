package com.mars.cloud.rocketmq.consume;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的已处理事件登记，只供测试与本机演示：进程重启即丢失，也不参与数据库事务。
 *
 * @since 2026-09-22
 */
public final class InMemoryProcessedEventStore implements ProcessedEventStore {

    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markProcessed(String consumerGroup, String eventId) {
        return processed.add(consumerGroup + "\u0000" + eventId);
    }

    public int size() {
        return processed.size();
    }
}
