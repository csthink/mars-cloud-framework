package com.mars.cloud.rocketmq.consume;

/**
 * 已处理事件的登记，供 {@link IdempotentEventHandler} 吞掉同一事件的重复投递。
 *
 * <p>实现必须与业务写入在同一个数据库事务里：业务失败回滚时登记一并撤销，消息重投后能再次处理。
 * 建议的 MySQL 表：
 * <pre>{@code
 * CREATE TABLE processed_event (
 *   consumer_group VARCHAR(128) NOT NULL,
 *   event_id       VARCHAR(64)  NOT NULL,
 *   processed_at   DATETIME(3)  NOT NULL,
 *   PRIMARY KEY (consumer_group, event_id)
 * );
 * }</pre>
 * {@code markProcessed} 对应 {@code INSERT IGNORE}（或捕获主键冲突）并按影响行数返回。
 *
 * @since 2026-09-22
 */
public interface ProcessedEventStore {

    /**
     * 登记事件已处理。
     *
     * @param consumerGroup 带运行环境前缀的消费组名
     * @param eventId 事件标识
     * @return 首次登记返回 true；已存在返回 false
     */
    boolean markProcessed(String consumerGroup, String eventId);
}
