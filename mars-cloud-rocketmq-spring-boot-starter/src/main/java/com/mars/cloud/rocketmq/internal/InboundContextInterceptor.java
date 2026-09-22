package com.mars.cloud.rocketmq.internal;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.rocketmq.RocketMqHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.ChannelInterceptor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 消费 binding 通道上的拦截器：在消费函数执行前还原上游 trace 与调用方身份，执行后按后进先出关闭。
 *
 * <p>消费函数在通道 {@code send} 内部被同步调用，{@code preSend} 与 {@code afterSendCompletion} 在同一线程，
 * 作用域覆盖整个函数执行。三个身份头缺任何一个都不建立身份，按匿名处理。
 */
public final class InboundContextInterceptor implements ChannelInterceptor {

    private final MessageTracing tracing;
    private final ThreadLocal<Deque<Frame>> frames = ThreadLocal.withInitial(ArrayDeque::new);

    private record Frame(Message<?> message, MessageTracing.Scope span, CallerContextHolder.Scope caller) {
    }

    public InboundContextInterceptor(MessageTracing tracing) {
        this.tracing = tracing;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        Object topic = headers.get(RocketMqHeaders.RECEIVED_TOPIC);
        MessageTracing.Scope span = tracing.startConsumer(topic == null ? "unknown" : topic.toString(), headers);
        CallerContextHolder.Scope caller = openCaller(headers);
        frames.get().push(new Frame(message, span, caller));
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        Deque<Frame> stack = frames.get();
        Frame frame = stack.peek();
        if (frame == null) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            frames.remove();
        }
        try {
            if (frame.caller() != null) {
                frame.caller().close();
            }
        } finally {
            frame.span().error(ex);
            frame.span().close();
        }
    }

    private static CallerContextHolder.Scope openCaller(MessageHeaders headers) {
        String subject = text(headers, InternalCallHeaders.SUBJECT);
        String clientId = text(headers, InternalCallHeaders.CLIENT_ID);
        String tenantId = text(headers, InternalCallHeaders.TENANT_ID);
        if (subject == null || clientId == null || tenantId == null) {
            return null;
        }
        return CallerContextHolder.open(new CallerContext(subject, clientId, tenantId));
    }

    private static String text(MessageHeaders headers, String name) {
        Object value = headers.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
