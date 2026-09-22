package com.mars.cloud.rocketmq.publish;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.MessagePublishException;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBinding;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBindingCatalog;
import com.mars.cloud.rocketmq.internal.EventMessages;
import com.mars.cloud.rocketmq.internal.MarsTransactionListener;
import com.mars.cloud.rocketmq.internal.MessageTracing;
import com.mars.cloud.rocketmq.internal.PendingLocalTransaction;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Objects;

/**
 * 事务消息发送：写库与发消息绑定成「本地事务提交，消息才可见」。
 *
 * <p>调用方传入本地事务，starter 先发半消息，再在同一线程执行本地事务，正常返回即提交消息，抛出任何 Throwable 即回滚消息并把
 * 它包成 {@link MessagePublishException} 抛回。本地事务里不能再发事务消息。进程在本地事务提交后、答复 broker 前崩溃时，broker 回查
 * {@link TransactionStateChecker}。不能在外层 Spring 事务里调用：外层事务会让本地提交与消息提交脱钩。
 * 事务消息忽略延迟档，延迟消息用 {@link PlainEventPublisher}。
 *
 * @since 2026-09-22
 */
public final class TransactionalEventPublisher {

    private final StreamBridge streamBridge;
    private final RocketMqBindingCatalog catalog;
    private final MessageTracing tracing;

    public TransactionalEventPublisher(StreamBridge streamBridge, RocketMqBindingCatalog catalog, MessageTracing tracing) {
        this.streamBridge = streamBridge;
        this.catalog = catalog;
        this.tracing = tracing;
    }

    /**
     * 发送一条事务消息。
     *
     * @param binding 生产 binding 名，必须配置为 {@code producer-type: Trans} 且监听器为 starter 的监听器
     * @param envelope 信封；{@code trace_id} 为 null 时按当前 span 补上
     * @param localTransaction 与消息绑定的本地事务
     * @param <T> 事件内容类型
     * @throws MessagePublishException 校验失败、发送失败或本地事务抛出异常
     */
    public <T> void publish(String binding, EventEnvelope<T> envelope, LocalTransaction localTransaction) {
        Objects.requireNonNull(envelope, "envelope 不能为空");
        Objects.requireNonNull(localTransaction, "localTransaction 不能为空");
        RocketMqBinding target = catalog.find(binding)
                .orElseThrow(() -> new MessagePublishException("binding [" + binding + "] 没有配置"));
        if (!target.transactional() || !MarsTransactionListener.BEAN_NAME.equals(target.transactionListener())) {
            throw new MessagePublishException("binding [" + binding + "] 不是事务生产者：必须配置 producer-type: Trans 与 transaction-listener: "
                    + MarsTransactionListener.BEAN_NAME);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MessagePublishException("不能在外层 Spring 事务里发送事务消息：外层事务会让本地提交与消息提交脱钩，请把本地事务放进 LocalTransaction");
        }
        EventEnvelope<T> outgoing = envelope.traceId() == null ? envelope.withTraceId(tracing.currentTraceId()) : envelope;
        Map<String, Object> headers = EventMessages.headers(outgoing, null);
        PendingLocalTransaction pending = PendingLocalTransaction.register(localTransaction);
        try (MessageTracing.Scope scope = tracing.startProducer(target.topic(), headers)) {
            boolean sent;
            try {
                sent = streamBridge.send(binding, EventMessages.message(outgoing, headers));
            } catch (RuntimeException e) {
                scope.error(e);
                if (pending.executed() && pending.failure() == null) {
                    throw new MessagePublishException("本地事务已执行，但事务消息的提交答复失败，消息状态待 broker 回查 binding="
                            + binding + " key=" + outgoing.key(), e);
                }
                throw new MessagePublishException("事务消息发送失败，本地事务未执行 binding=" + binding + " key=" + outgoing.key(), e);
            }
            if (pending.failure() != null) {
                scope.error(pending.failure());
                throw new MessagePublishException("本地事务失败，消息已回滚 binding=" + binding + " key=" + outgoing.key(), pending.failure());
            }
            if (!sent) {
                throw new MessagePublishException("事务消息未被 binder 接受 binding=" + binding + " key=" + outgoing.key());
            }
            if (!pending.executed()) {
                throw new MessagePublishException("事务监听器没有执行本地事务，消息状态未知 binding=" + binding + " key=" + outgoing.key());
            }
        } finally {
            pending.release();
        }
    }
}
