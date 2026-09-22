package com.mars.cloud.rocketmq;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.internal.MarsTransactionListener;
import com.mars.cloud.rocketmq.internal.PendingLocalTransaction;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MarsTransactionListenerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private TransactionStateChecker checker(String topic, LocalTransactionState answer, AtomicReference<EventEnvelope<?>> seen) {
        return new TransactionStateChecker() {
            @Override
            public boolean supports(String t) {
                return topic.equals(t);
            }

            @Override
            public LocalTransactionState check(EventEnvelope<?> envelope, MessageExt raw) {
                seen.set(envelope);
                return answer;
            }
        };
    }

    @Test
    void executesTheRegisteredLocalTransactionOnTheSendingThreadAndCommits() {
        MarsTransactionListener listener = new MarsTransactionListener(List.of(), mapper, "");
        AtomicReference<Thread> ran = new AtomicReference<>();
        PendingLocalTransaction pending = PendingLocalTransaction.register(() -> ran.set(Thread.currentThread()));

        LocalTransactionState state = listener.executeLocalTransaction(new Message("order-event", "PAID", "k", new byte[0]), null);

        assertThat(state).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(ran.get()).isSameAs(Thread.currentThread());
        assertThat(pending.executed()).isTrue();
        assertThat(pending.executedOn()).isSameAs(Thread.currentThread());
        assertThat(pending.failure()).isNull();
        pending.release();
    }

    @Test
    void localTransactionFailureRollsBackAndKeepsTheCause() {
        MarsTransactionListener listener = new MarsTransactionListener(List.of(), mapper, "");
        IllegalStateException boom = new IllegalStateException("insert failed");
        PendingLocalTransaction pending = PendingLocalTransaction.register(() -> { throw boom; });

        LocalTransactionState state = listener.executeLocalTransaction(new Message("order-event", "PAID", "k", new byte[0]), null);

        assertThat(state).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThat(pending.executed()).isTrue();
        assertThat(pending.failure()).isSameAs(boom);
        pending.release();
    }

    @Test
    void messageWithoutRegisteredTransactionIsRolledBack() {
        MarsTransactionListener listener = new MarsTransactionListener(List.of(), mapper, "");
        assertThat(listener.executeLocalTransaction(new Message("order-event", "PAID", "k", new byte[0]), null))
                .isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void registrationCannotNest() {
        PendingLocalTransaction first = PendingLocalTransaction.register(() -> { });
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> PendingLocalTransaction.register(() -> { }))
                .withMessageContaining("不能嵌套");
        first.release();
        PendingLocalTransaction again = PendingLocalTransaction.register(() -> { });
        again.release();
    }

    @Test
    void checkBackIsAnsweredByTheCheckerOfTheUnprefixedTopic() {
        AtomicReference<EventEnvelope<?>> seen = new AtomicReference<>();
        MarsTransactionListener listener = new MarsTransactionListener(
                List.of(checker("order-event", LocalTransactionState.COMMIT_MESSAGE, seen)), mapper, "s1-");
        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-order-service", "order-1", Map.of("amount", 5));
        MessageExt raw = new MessageExt();
        raw.setTopic("s1-order-event");
        raw.setBody(mapper.writeValueAsBytes(envelope));
        raw.putUserProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES, "1");

        assertThat(listener.checkLocalTransaction(raw)).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(seen.get().eventId()).isEqualTo(envelope.eventId());
        assertThat(seen.get().key()).isEqualTo("order-1");
        assertThat(seen.get().payload()).isInstanceOf(tools.jackson.databind.JsonNode.class);
    }

    @Test
    void checkBackOnTheHalfTopicUsesTheRealTopicProperty() {
        AtomicReference<EventEnvelope<?>> seen = new AtomicReference<>();
        MarsTransactionListener listener = new MarsTransactionListener(
                List.of(checker("order-event", LocalTransactionState.ROLLBACK_MESSAGE, seen)), mapper, "");
        MessageExt raw = new MessageExt();
        raw.setTopic("RMQ_SYS_TRANS_HALF_TOPIC");
        org.apache.rocketmq.common.message.MessageAccessor.putProperty(raw, MessageConst.PROPERTY_REAL_TOPIC, "order-event");
        raw.setBody(mapper.writeValueAsBytes(EventEnvelope.of("PAID", "mars-cloud-order-service", "order-2", null)));

        assertThat(listener.checkLocalTransaction(raw)).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThat(seen.get()).isNotNull();
    }

    @Test
    void checkBackWithoutCheckerOrWithBadBodyAnswersUnknown() {
        MarsTransactionListener listener = new MarsTransactionListener(List.of(), mapper, "");
        MessageExt raw = new MessageExt();
        raw.setTopic("order-event");
        raw.setBody(mapper.writeValueAsBytes(EventEnvelope.of("PAID", "mars-cloud-order-service", "order-3", null)));
        assertThat(listener.checkLocalTransaction(raw)).isEqualTo(LocalTransactionState.UNKNOW);

        MarsTransactionListener withChecker = new MarsTransactionListener(
                List.of(checker("order-event", LocalTransactionState.COMMIT_MESSAGE, new AtomicReference<>())), mapper, "");
        MessageExt junk = new MessageExt();
        junk.setTopic("order-event");
        junk.setBody("not json".getBytes());
        assertThat(withChecker.checkLocalTransaction(junk)).isEqualTo(LocalTransactionState.UNKNOW);

        MessageExt wrongPrefix = new MessageExt();
        wrongPrefix.setTopic("order-event");
        wrongPrefix.setBody(raw.getBody());
        MarsTransactionListener prefixed = new MarsTransactionListener(
                List.of(checker("order-event", LocalTransactionState.COMMIT_MESSAGE, new AtomicReference<>())), mapper, "s1-");
        assertThat(prefixed.checkLocalTransaction(wrongPrefix)).isEqualTo(LocalTransactionState.UNKNOW);
    }
}
