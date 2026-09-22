package com.mars.cloud.rocketmq;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.consume.IdempotentEventHandler;
import com.mars.cloud.rocketmq.consume.InMemoryProcessedEventStore;
import com.mars.cloud.rocketmq.consume.ProcessedEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentEventHandlerTest {

    private static final String GROUP = "s1-mars-cloud-order-service-order-event";

    private static Message<EventEnvelope<Map<String, Object>>> message(EventEnvelope<Map<String, Object>> envelope) {
        return MessageBuilder.withPayload(envelope).setHeader(RocketMqHeaders.RECEIVED_TOPIC, "s1-order-event").build();
    }

    @Test
    void sameEventIsProcessedOnceAndDuplicatesAreSkipped() {
        IdempotentEventHandler handler = new IdempotentEventHandler(new InMemoryProcessedEventStore(), TransactionOperations.withoutTransaction());
        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-order-service", "order-1", Map.of());
        AtomicInteger executed = new AtomicInteger();

        assertThat(handler.handle(GROUP, message(envelope), e -> executed.incrementAndGet())).isTrue();
        assertThat(handler.handle(GROUP, message(envelope), e -> executed.incrementAndGet())).isFalse();
        assertThat(executed.get()).isEqualTo(1);

        // 另一个消费组处理同一事件不受影响
        assertThat(handler.handle("other-group", message(envelope), e -> executed.incrementAndGet())).isTrue();
        assertThat(executed.get()).isEqualTo(2);
    }

    @Test
    void businessFailureRollsBackTheRegistrationSoRedeliveryCanRetry() {
        StreamTestSupport.RecordingTransactionManager manager = new StreamTestSupport.RecordingTransactionManager();
        TransactionalStore store = new TransactionalStore();
        IdempotentEventHandler handler = new IdempotentEventHandler(store, new TransactionTemplate(manager));
        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-order-service", "order-2", Map.of());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> handler.handle(GROUP, message(envelope), e -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("first attempt fails");
        })).isInstanceOf(IllegalStateException.class).hasMessage("first attempt fails");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(store.committed).isEmpty();

        // 重投后成功，登记随事务提交
        assertThat(handler.handle(GROUP, message(envelope), e -> attempts.incrementAndGet())).isTrue();
        assertThat(manager.commits).isEqualTo(1);
        assertThat(store.committed).containsExactly(GROUP + "/" + envelope.eventId());
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(handler.handle(GROUP, message(envelope), e -> attempts.incrementAndGet())).isFalse();
        assertThat(attempts.get()).isEqualTo(2);
    }

    /** 登记只在事务提交后生效、回滚即撤销的存储替身。 */
    static final class TransactionalStore implements ProcessedEventStore {
        final Set<String> committed = new HashSet<>();
        private final Set<String> pending = new HashSet<>();

        @Override
        public boolean markProcessed(String consumerGroup, String eventId) {
            String key = consumerGroup + "/" + eventId;
            if (committed.contains(key) || pending.contains(key)) {
                return false;
            }
            pending.add(key);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    pending.remove(key);
                    if (status == STATUS_COMMITTED) {
                        committed.add(key);
                    }
                }
            });
            return true;
        }
    }
}
