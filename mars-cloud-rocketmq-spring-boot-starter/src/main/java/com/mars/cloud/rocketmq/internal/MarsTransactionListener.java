package com.mars.cloud.rocketmq.internal;

import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.common.messaging.MessagingNames;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * starter 提供的唯一事务监听器，bean 名固定为 {@value #BEAN_NAME}。
 *
 * <p>{@code executeLocalTransaction} 执行发布器登记的本地事务：正常返回答提交，抛出任何 Throwable 都答回滚；没有登记说明
 * 这条事务消息不是经发布器发出的，答回滚并记录 ERROR。{@code checkLocalTransaction} 按主题找唯一的
 * {@link TransactionStateChecker} 答复；找不到答 {@code UNKNOW} 让 broker 继续回查，而不是静默丢弃。
 */
public final class MarsTransactionListener implements TransactionListener {

    public static final String BEAN_NAME = "marsTransactionListener";

    private static final Logger log = LoggerFactory.getLogger(MarsTransactionListener.class);
    private static final String HALF_TOPIC = "RMQ_SYS_TRANS_HALF_TOPIC";
    private static final TypeReference<EventEnvelope<JsonNode>> ENVELOPE = new TypeReference<>() { };

    private final List<TransactionStateChecker> checkers;
    private final ObjectMapper objectMapper;
    private final String prefix;

    public MarsTransactionListener(List<TransactionStateChecker> checkers, ObjectMapper objectMapper, String prefix) {
        this.checkers = List.copyOf(checkers);
        this.objectMapper = objectMapper;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
        PendingLocalTransaction pending = PendingLocalTransaction.take();
        if (pending == null) {
            log.error("事务消息 topic={} keys={} 没有经 TransactionalEventPublisher 登记本地事务，按回滚处理",
                    message.getTopic(), message.getKeys());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            pending.execute();
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (Throwable e) {
            log.warn("事务消息 topic={} keys={} 的本地事务失败，消息回滚: {}", message.getTopic(), message.getKeys(), e.toString());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        String topic = realTopic(message);
        String rawTopic;
        try {
            rawTopic = MessagingNames.stripPrefix(prefix, topic);
        } catch (IllegalArgumentException e) {
            log.error("回查的事务消息 topic={} 不符合命名规则，答 UNKNOW: {}", topic, e.getMessage());
            return LocalTransactionState.UNKNOW;
        }
        List<TransactionStateChecker> supporting = checkers.stream().filter(c -> c.supports(rawTopic)).toList();
        if (supporting.size() != 1) {
            log.error("回查的事务消息 topic={} 没有唯一的 TransactionStateChecker（当前 {} 个），答 UNKNOW", topic, supporting.size());
            return LocalTransactionState.UNKNOW;
        }
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), ENVELOPE);
        } catch (RuntimeException e) {
            log.error("回查的事务消息 topic={} msgId={} 的消息体不是 EventEnvelope，答 UNKNOW: {}", topic, message.getMsgId(), e.toString());
            return LocalTransactionState.UNKNOW;
        }
        LocalTransactionState state = supporting.get(0).check(envelope, message);
        log.info("回查事务消息 topic={} event_id={} 第 {} 次，答复 {}", topic, envelope.eventId(),
                message.getUserProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES), state);
        return state == null ? LocalTransactionState.UNKNOW : state;
    }

    private static String realTopic(MessageExt message) {
        String topic = message.getTopic();
        if (HALF_TOPIC.equals(topic)) {
            String real = message.getUserProperty(MessageConst.PROPERTY_REAL_TOPIC);
            return real == null ? topic : real;
        }
        return topic;
    }
}
