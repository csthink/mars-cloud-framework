package com.mars.cloud.rocketmq.publish;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.DelayLevel;
import com.mars.cloud.rocketmq.MessagePublishException;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBinding;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBindingCatalog;
import com.mars.cloud.rocketmq.internal.EventMessages;
import com.mars.cloud.rocketmq.internal.MessageTracing;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Objects;

/**
 * 普通消息发送：不与本地事务绑定，是延迟消息的唯一入口。
 *
 * <p>要和本地事务绑定时用 {@link #publishAfterCommit}：有活动事务就登记到事务提交后发送，没有就立即发送。
 * 提交后、发送前进程崩溃会丢这条消息，兜底由对账任务承担，不在消息层。
 *
 * @since 2026-09-22
 */
public final class PlainEventPublisher {

    private final StreamBridge streamBridge;
    private final RocketMqBindingCatalog catalog;
    private final MessageTracing tracing;

    public PlainEventPublisher(StreamBridge streamBridge, RocketMqBindingCatalog catalog, MessageTracing tracing) {
        this.streamBridge = streamBridge;
        this.catalog = catalog;
        this.tracing = tracing;
    }

    /** 立即发送。 */
    public <T> void publish(String binding, EventEnvelope<T> envelope) {
        publish(binding, envelope, null);
    }

    /**
     * 立即发送一条固定档延迟消息。
     *
     * @param delay 延迟档，null 表示不延迟
     */
    public <T> void publish(String binding, EventEnvelope<T> envelope, DelayLevel delay) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        RocketMqBinding target = catalog.find(binding)
                .orElseThrow(() -> new MessagePublishException("binding [" + binding + "] 没有配置"));
        if (target.kind() != RocketMqBinding.Kind.PRODUCER || target.transactional()) {
            throw new MessagePublishException("binding [" + binding + "] 不是普通生产者：延迟消息与非事务消息只能经 producer-type: Normal 的 binding 发送");
        }
        EventEnvelope<T> outgoing = envelope.traceId() == null ? envelope.withTraceId(tracing.currentTraceId()) : envelope;
        Map<String, Object> headers = EventMessages.headers(outgoing, delay);
        try (MessageTracing.Scope scope = tracing.startProducer(target.topic(), headers)) {
            boolean sent;
            try {
                sent = streamBridge.send(binding, EventMessages.message(outgoing, headers));
            } catch (RuntimeException e) {
                scope.error(e);
                throw new MessagePublishException("消息发送失败 binding=" + binding + " key=" + outgoing.key(), e);
            }
            if (!sent) {
                throw new MessagePublishException("消息未被 binder 接受 binding=" + binding + " key=" + outgoing.key());
            }
        }
    }

    /**
     * 在当前 Spring 事务提交后发送；没有活动事务时立即发送。
     *
     * @param delay 延迟档，null 表示不延迟
     */
    public <T> void publishAfterCommit(String binding, EventEnvelope<T> envelope, DelayLevel delay) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        catalog.find(binding).orElseThrow(() -> new MessagePublishException("binding [" + binding + "] 没有配置"));
        if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(binding, envelope, delay);
                }
            });
            return;
        }
        publish(binding, envelope, delay);
    }
}
