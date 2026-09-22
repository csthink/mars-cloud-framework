package com.mars.cloud.rocketmq.consume;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.RocketMqHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 消费幂等助手：同一 {@code event_id} 只执行一次业务处理。
 *
 * <p>在一个事务里先登记再执行业务：登记返回 false 说明已处理过，跳过并记录；业务抛异常时整个事务回滚
 * （登记一并撤销），异常继续向外抛以触发 RocketMQ 重投。业务表状态仍是第一道幂等，本助手是第二道。
 *
 * @since 2026-09-22
 */
public final class IdempotentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEventHandler.class);

    private final ProcessedEventStore store;
    private final TransactionOperations transaction;
    private final String topicHeader;

    /**
     * @param store 已处理事件登记
     * @param transaction 事务模板；没有事务管理器时传 {@link TransactionOperations#withoutTransaction()}
     */
    public IdempotentEventHandler(ProcessedEventStore store, TransactionOperations transaction) {
        this(store, transaction, RocketMqHeaders.RECEIVED_TOPIC);
    }

    IdempotentEventHandler(ProcessedEventStore store, TransactionOperations transaction, String topicHeader) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不能为空");
        this.topicHeader = topicHeader;
    }

    /**
     * 按消费组与事件标识只处理一次。
     *
     * @param consumerGroup 登记用的消费组名，按配置里不带前缀的 group 写；同一应用的多个消费函数各用自己的组名
     * @param message 收到的消息
     * @param business 业务处理
     * @param <T> 事件内容类型
     * @return 本次是否执行了业务处理
     */
    public <T> boolean handle(String consumerGroup, Message<EventEnvelope<T>> message, Consumer<EventEnvelope<T>> business) {
        Objects.requireNonNull(message, "message 不能为空");
        EventEnvelope<T> envelope = message.getPayload();
        Boolean executed = transaction.execute(status -> {
            if (!store.markProcessed(consumerGroup, envelope.eventId())) {
                log.info("跳过重复投递的事件 group={} event_id={} key={} topic={}", consumerGroup, envelope.eventId(),
                        envelope.key(), message.getHeaders().get(topicHeader));
                return false;
            }
            business.accept(envelope);
            return true;
        });
        return Boolean.TRUE.equals(executed);
    }
}
