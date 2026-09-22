package com.mars.cloud.common.messaging;

/**
 * 消息在服务之间传递时使用的、与消息中间件无关的固定头名。
 *
 * <p>调用方身份仍用 {@link com.mars.cloud.common.context.InternalCallHeaders} 的三个头名，
 * 消息头与 HTTP 请求头保持同一套字段。中间件特有的头名（tag、key、延迟档）由对应的 starter 定义。
 *
 * @since 2026-09-21
 */
public final class MessagingHeaders {

    /** W3C Trace Context 的主头，值形如 {@code 00-<trace-id>-<span-id>-<flags>}。 */
    public static final String TRACEPARENT = "traceparent";

    /** W3C Trace Context 的厂商扩展头，可以缺席。 */
    public static final String TRACESTATE = "tracestate";

    /** 与消息体 {@link EventEnvelope#eventId()} 相同的事件标识，供幂等登记与排查使用。 */
    public static final String EVENT_ID = "X-Mars-Event-Id";

    private MessagingHeaders() {
    }
}
