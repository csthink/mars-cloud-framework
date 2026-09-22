package com.mars.cloud.rocketmq.internal;

import org.springframework.messaging.MessageHeaders;

import java.util.Map;

/**
 * 消息头里 trace 上下文的写入与还原。
 *
 * <p>有 Micrometer Tracing 时由 {@link MicrometerMessageTracing} 实现；没有时用 {@link #NONE}，全部为空操作，
 * 只传身份头。接口本身不引用 Micrometer 类型，缺依赖时也能加载。
 */
public interface MessageTracing {

    /** 没有 Tracer 时的实现。 */
    MessageTracing NONE = new MessageTracing() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String currentTraceId() {
            return null;
        }

        @Override
        public Scope startProducer(String topic, Map<String, Object> headers) {
            return Scope.NONE;
        }

        @Override
        public Scope startConsumer(String topic, MessageHeaders headers) {
            return Scope.NONE;
        }
    };

    boolean enabled();

    /** 当前 span 的 trace 标识，没有时为 null。 */
    String currentTraceId();

    /** 生产侧：开一个 PRODUCER span 并把它的上下文写进消息头；返回的作用域必须在发送结束后关闭。 */
    Scope startProducer(String topic, Map<String, Object> headers);

    /** 消费侧：从消息头还原上游上下文并开一个 CONSUMER span；返回的作用域必须在消费函数结束后关闭。 */
    Scope startConsumer(String topic, MessageHeaders headers);

    /** 一个 span 与它在当前线程上的作用域。 */
    interface Scope extends AutoCloseable {

        Scope NONE = new Scope() {
            @Override
            public void error(Throwable error) {
            }

            @Override
            public void close() {
            }
        };

        void error(Throwable error);

        @Override
        void close();
    }
}
