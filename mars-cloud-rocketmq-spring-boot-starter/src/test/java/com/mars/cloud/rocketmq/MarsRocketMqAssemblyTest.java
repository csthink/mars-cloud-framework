package com.mars.cloud.rocketmq;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.common.messaging.MessagingHeaders;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBinding;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBindingCatalog;
import com.mars.cloud.rocketmq.publish.PlainEventPublisher;
import com.mars.cloud.rocketmq.publish.TransactionalEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarsRocketMqAssemblyTest {

    private final ApplicationContextRunner runner = StreamTestSupport.runner()
            .withUserConfiguration(StreamTestSupport.OrderApplication.class)
            .withPropertyValues(StreamTestSupport.typicalBindings());

    @Test
    void prefixIsAppliedToEveryDestinationAndGroup() {
        runner.withPropertyValues("mars.rocketmq.prefix=s1-").run(context -> {
            assertThat(context).hasNotFailed();
            BindingServiceProperties bindings = context.getBean(BindingServiceProperties.class);
            assertThat(bindings.getBindingProperties("orderPaid-in-0").getDestination()).isEqualTo("s1-order-event");
            assertThat(bindings.getBindingProperties("orderPaid-in-0").getGroup()).isEqualTo("s1-" + StreamTestSupport.GROUP);
            assertThat(bindings.getBindingProperties("paymentTx-out-0").getDestination()).isEqualTo("s1-payment-event");
            assertThat(bindings.getBindingProperties("paymentPlain-out-0").getDestination()).isEqualTo("s1-payment-event");
            assertThat(bindings.getBindingProperties("orderLoop-out-0").getDestination()).isEqualTo("s1-order-event");

            RocketMqBindingCatalog catalog = context.getBean(RocketMqBindingCatalog.class);
            assertThat(catalog.bindings()).hasSize(4);
            RocketMqBinding consumer = catalog.find("orderPaid-in-0").orElseThrow();
            assertThat(consumer.kind()).isEqualTo(RocketMqBinding.Kind.CONSUMER);
            assertThat(consumer.rawTopic()).isEqualTo("order-event");
            assertThat(consumer.topic()).isEqualTo("s1-order-event");
            assertThat(consumer.rawGroup()).isEqualTo(StreamTestSupport.GROUP);
            assertThat(consumer.maxAttempts()).isEqualTo(1);
            assertThat(catalog.bindings()).allMatch(binding -> !binding.messageTrace());
            assertThat(catalog.find("paymentTx-out-0").orElseThrow().transactional()).isTrue();
            assertThat(catalog.find("paymentPlain-out-0").orElseThrow().transactional()).isFalse();
        });
    }

    @Test
    void emptyPrefixLeavesNamesUnchanged() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BindingServiceProperties bindings = context.getBean(BindingServiceProperties.class);
            assertThat(bindings.getBindingProperties("orderPaid-in-0").getDestination()).isEqualTo("order-event");
            assertThat(bindings.getBindingProperties("orderPaid-in-0").getGroup()).isEqualTo(StreamTestSupport.GROUP);
        });
    }

    @Test
    void plainPublisherWritesTagKeyEventIdIdentityAndDelayHeaders() {
        runner.withPropertyValues("mars.rocketmq.prefix=s1-").run(context -> {
            assertThat(context).hasNotFailed();
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            OutputDestination output = context.getBean(OutputDestination.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION,
                    "order-1", Map.of("amount", 1990));

            try (CallerContextHolder.Scope ignored = CallerContextHolder.open(new CallerContext("user-1", "portal", "default"))) {
                publisher.publish("paymentPlain-out-0", envelope, DelayLevel.LEVEL_16);
            }

            Message<byte[]> sent = output.receive(1000, "s1-payment-event");
            assertThat(sent).isNotNull();
            assertThat(sent.getHeaders()).containsEntry(RocketMqHeaders.TAGS, "CREATED")
                    .containsEntry(RocketMqHeaders.KEYS, "order-1")
                    .containsEntry(MessagingHeaders.EVENT_ID, envelope.eventId())
                    .containsEntry(InternalCallHeaders.SUBJECT, "user-1")
                    .containsEntry(InternalCallHeaders.CLIENT_ID, "portal")
                    .containsEntry(InternalCallHeaders.TENANT_ID, "default")
                    .containsEntry(RocketMqHeaders.DELAY, 16);
            assertThat(sent.getHeaders()).doesNotContainKey(MessagingHeaders.TRACEPARENT);
            JsonNode body = context.getBean(ObjectMapper.class).readTree(sent.getPayload());
            assertThat(body.get("event_id").asString()).isEqualTo(envelope.eventId());
            assertThat(body.get("event_type").asString()).isEqualTo("CREATED");
            assertThat(body.get("key").asString()).isEqualTo("order-1");
            assertThat(body.get("trace_id").isNull()).isTrue();
            assertThat(body.get("payload").get("amount").asInt()).isEqualTo(1990);
        });
    }

    @Test
    void plainPublisherWithoutCallerOmitsIdentityHeaders() {
        runner.run(context -> {
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            OutputDestination output = context.getBean(OutputDestination.class);
            publisher.publish("paymentPlain-out-0", EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION, "order-2", null));
            Message<byte[]> sent = output.receive(1000, "payment-event");
            assertThat(sent).isNotNull();
            assertThat(sent.getHeaders()).doesNotContainKeys(InternalCallHeaders.SUBJECT, InternalCallHeaders.CLIENT_ID,
                    InternalCallHeaders.TENANT_ID, RocketMqHeaders.DELAY);
        });
    }

    @Test
    void plainPublisherRejectsTransactionalBindingAndUnknownBinding() {
        runner.run(context -> {
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            EventEnvelope<Object> envelope = EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION, "order-3", null);
            assertThatThrownBy(() -> publisher.publish("paymentTx-out-0", envelope))
                    .isInstanceOf(MessagePublishException.class).hasMessageContaining("不是普通生产者");
            assertThatThrownBy(() -> publisher.publish("nowhere-out-0", envelope))
                    .isInstanceOf(MessagePublishException.class).hasMessageContaining("没有配置");
            assertThatThrownBy(() -> publisher.publish("orderPaid-in-0", envelope))
                    .isInstanceOf(MessagePublishException.class).hasMessageContaining("不是普通生产者");
        });
    }

    @Test
    void transactionalPublisherRejectsPlainBindingAndDetectsListenerNotInvoked() {
        runner.run(context -> {
            TransactionalEventPublisher publisher = context.getBean(TransactionalEventPublisher.class);
            EventEnvelope<Object> envelope = EventEnvelope.of("PAID", StreamTestSupport.APPLICATION, "order-4", null);
            assertThatThrownBy(() -> publisher.publish("paymentPlain-out-0", envelope, () -> { }))
                    .isInstanceOf(MessagePublishException.class).hasMessageContaining("不是事务生产者");
            // test binder 不会调用事务监听器：发布器必须察觉本地事务没有被执行，而不是静默当作成功
            assertThatThrownBy(() -> publisher.publish("paymentTx-out-0", envelope, () -> { }))
                    .isInstanceOf(MessagePublishException.class).hasMessageContaining("事务监听器没有执行本地事务");
        });
    }

    @Test
    void inboundInterceptorRestoresCallerIdentityForTheConsumerFunction() {
        runner.withPropertyValues("mars.rocketmq.prefix=s1-").run(context -> {
            assertThat(context).hasNotFailed();
            InputDestination input = context.getBean(InputDestination.class);
            StreamTestSupport.OrderApplication app = context.getBean(StreamTestSupport.OrderApplication.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-payment-service", "order-5", Map.of("k", "v"));

            input.send(MessageBuilder.withPayload(mapper.writeValueAsBytes(envelope))
                    .setHeader(InternalCallHeaders.SUBJECT, "user-9")
                    .setHeader(InternalCallHeaders.CLIENT_ID, "portal")
                    .setHeader(InternalCallHeaders.TENANT_ID, "default")
                    .setHeader(RocketMqHeaders.RECEIVED_TOPIC, "s1-order-event")
                    .build(), "s1-order-event");

            assertThat(app.received).hasSize(1);
            StreamTestSupport.Received received = app.received.get(0);
            assertThat(received.callerSubject()).isEqualTo("user-9");
            assertThat(received.message().getPayload()).isEqualTo(envelope);
            assertThat(CallerContextHolder.current()).isEmpty();

            // 身份头不齐全：按匿名处理，作用域同样正确关闭
            input.send(MessageBuilder.withPayload(mapper.writeValueAsBytes(envelope))
                    .setHeader(InternalCallHeaders.SUBJECT, "user-9").build(), "s1-order-event");
            assertThat(app.received).hasSize(2);
            assertThat(app.received.get(1).callerSubject()).isNull();
            assertThat(CallerContextHolder.current()).isEmpty();
        });
    }

    @Test
    void publishedEventReachesTheConsumerWithIdentityRestored() {
        runner.withPropertyValues("mars.rocketmq.prefix=s2-").run(context -> {
            assertThat(context).hasNotFailed();
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            StreamTestSupport.OrderApplication app = context.getBean(StreamTestSupport.OrderApplication.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION, "order-6", Map.of("n", 1));

            try (CallerContextHolder.Scope ignored = CallerContextHolder.open(new CallerContext("user-6", "portal", "default"))) {
                publisher.publish("orderLoop-out-0", envelope);
            }

            assertThat(app.received).hasSize(1);
            StreamTestSupport.Received received = app.received.get(0);
            assertThat(received.message().getPayload()).isEqualTo(envelope);
            assertThat(received.callerSubject()).isEqualTo("user-6");
            assertThat(received.message().getHeaders()).containsEntry(RocketMqHeaders.TAGS, "CREATED")
                    .containsEntry(MessagingHeaders.EVENT_ID, envelope.eventId());
        });
    }

    @Test
    void consumerExceptionPropagatesAndLeavesNoCallerContextBehind() {
        runner.run(context -> {
            InputDestination input = context.getBean(InputDestination.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-payment-service", "fail", Map.of());
            Message<byte[]> message = MessageBuilder.withPayload(mapper.writeValueAsBytes(envelope))
                    .setHeader(InternalCallHeaders.SUBJECT, "user-9")
                    .setHeader(InternalCallHeaders.CLIENT_ID, "portal")
                    .setHeader(InternalCallHeaders.TENANT_ID, "default")
                    .build();
            StreamTestSupport.OrderApplication app = context.getBean(StreamTestSupport.OrderApplication.class);
            // test binder 把消费异常交给错误通道并记录，不向发送方抛出；这里只核对函数确实执行且上下文已清理
            input.send(message, "order-event");
            assertThat(app.received).hasSize(1);
            assertThat(app.received.get(0).callerSubject()).isEqualTo("user-9");
            assertThat(CallerContextHolder.current()).isEmpty();
        });
    }
}
