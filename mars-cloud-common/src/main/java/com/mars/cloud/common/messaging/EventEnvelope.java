package com.mars.cloud.common.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 事件消息的信封，是消息体的唯一形态。
 *
 * <p>字段名按蛇形序列化。{@code event_type} 与消息的 tag 相同；{@code key} 是业务键（如订单号），
 * 生产侧同时把它写入消息的 key，消费侧按它做业务幂等；{@code event_id} 同时写入
 * {@link MessagingHeaders#EVENT_ID} 头，供 {@code event_id} 级别的幂等登记与排查使用。
 *
 * @param eventId 事件标识，UUID 文本，生产侧生成
 * @param eventType 事件名，与 tag 相同，必须符合 {@link MessagingNames#TAG}
 * @param occurredAt 事件发生时刻
 * @param producer 生产方应用名
 * @param traceId 生产时的 trace 标识，没有 Tracer 时为 null
 * @param key 业务键
 * @param payload 事件内容，可以为 null
 * @param <T> 事件内容类型
 * @since 2026-09-21
 */
public record EventEnvelope<T>(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("producer") String producer,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("key") String key,
        @JsonProperty("payload") T payload) {

    @JsonCreator
    public EventEnvelope {
        eventId = requireText(eventId, "eventId");
        eventType = MessagingNames.requireTag(eventType);
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        producer = MessagingNames.requireName(producer, "producer");
        key = requireText(key, "key");
        if (traceId != null && traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为空白，没有 trace 时传 null");
        }
    }

    /**
     * 生成一个新事件：随机 UUID 作为 {@code event_id}，当前时刻作为 {@code occurred_at}，没有 trace。
     *
     * @param eventType 事件名
     * @param producer 生产方应用名
     * @param key 业务键
     * @param payload 事件内容
     * @param <T> 事件内容类型
     * @return 新信封
     */
    public static <T> EventEnvelope<T> of(String eventType, String producer, String key, T payload) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), eventType, Instant.now(), producer, null, key, payload);
    }

    /**
     * 返回带指定 trace 标识的副本。
     *
     * @param traceId trace 标识，传 null 表示去掉
     * @return 新信封
     */
    public EventEnvelope<T> withTraceId(String traceId) {
        return new EventEnvelope<>(eventId, eventType, occurredAt, producer, traceId, key, payload);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
