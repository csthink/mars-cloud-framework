package com.mars.cloud.rocketmq.internal;

import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.common.messaging.MessagingHeaders;
import com.mars.cloud.rocketmq.DelayLevel;
import com.mars.cloud.rocketmq.RocketMqHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把信封组装成带约定头的 Spring 消息：tag 为事件名、key 为业务键、事件标识头、调用方身份头、可选延迟档。
 * trace 头由 {@link MessageTracing} 在发送时写入同一个头集合。
 */
public final class EventMessages {

    private EventMessages() {
    }

    /** 组装消息头；返回可变 Map 供 tracing 追加 traceparent。 */
    public static Map<String, Object> headers(EventEnvelope<?> envelope, DelayLevel delay) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(RocketMqHeaders.TAGS, envelope.eventType());
        headers.put(RocketMqHeaders.KEYS, envelope.key());
        headers.put(MessagingHeaders.EVENT_ID, envelope.eventId());
        CallerContextHolder.current().ifPresent(context -> {
            headers.put(InternalCallHeaders.SUBJECT, context.subject());
            headers.put(InternalCallHeaders.CLIENT_ID, context.clientId());
            headers.put(InternalCallHeaders.TENANT_ID, context.tenantId());
        });
        if (delay != null) {
            headers.put(RocketMqHeaders.DELAY, delay.level());
        }
        return headers;
    }

    public static <T> Message<EventEnvelope<T>> message(EventEnvelope<T> envelope, Map<String, Object> headers) {
        return MessageBuilder.withPayload(envelope).copyHeaders(headers).build();
    }
}
