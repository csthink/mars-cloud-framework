package com.mars.cloud.rocketmq;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.tracecontext.TraceContextPropagation;
import brave.sampler.Sampler;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.common.messaging.MessagingHeaders;
import com.mars.cloud.rocketmq.internal.MessageTracing;
import com.mars.cloud.rocketmq.internal.MicrometerMessageTracing;
import com.mars.cloud.rocketmq.publish.PlainEventPublisher;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.binder.test.InputDestination;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTracingContractTest {

    @Configuration(proxyBeanMethods = false)
    static class BraveConfiguration {

        @Bean(destroyMethod = "close")
        Tracing braveTracing() {
            return Tracing.newBuilder()
                    .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder().build())
                    .propagationFactory(TraceContextPropagation.FACTORY)
                    .traceId128Bit(true)
                    .supportsJoin(false)
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .build();
        }

        @Bean
        Tracer tracer(Tracing tracing) {
            return new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
        }

        @Bean
        Propagator propagator(Tracing tracing) {
            return new BravePropagator(tracing);
        }
    }

    private final ApplicationContextRunner runner = StreamTestSupport.runner()
            .withUserConfiguration(BraveConfiguration.class, StreamTestSupport.OrderApplication.class)
            .withPropertyValues(StreamTestSupport.typicalBindings());

    @Test
    void tracingImplementationIsPickedWhenTracerAndPropagatorExist() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MessageTracing.class)).isInstanceOf(MicrometerMessageTracing.class);
        });
        StreamTestSupport.runner().withUserConfiguration(StreamTestSupport.OrderApplication.class)
                .withPropertyValues(StreamTestSupport.typicalBindings()).run(context ->
                        assertThat(context.getBean(MessageTracing.class)).isSameAs(MessageTracing.NONE));
    }

    @Test
    void publisherWritesW3cTraceparentOfAProducerSpanAndFillsTraceIdIntoTheEnvelope() {
        runner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            OutputDestination output = context.getBean(OutputDestination.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION, "order-7", Map.of());

            Span parent = tracer.nextSpan().name("http request").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                publisher.publish("paymentPlain-out-0", envelope);
            } finally {
                parent.end();
            }

            Message<byte[]> sent = output.receive(1000, "payment-event");
            assertThat(sent).isNotNull();
            String traceparent = (String) sent.getHeaders().get(MessagingHeaders.TRACEPARENT);
            assertThat(traceparent).matches("00-" + parent.context().traceId() + "-[0-9a-f]{16}-01");
            // 第三段是生产 span 的 span id，不是外层 span 的
            assertThat(traceparent.split("-")[2]).isNotEqualTo(parent.context().spanId());
            assertThat(sent.getHeaders()).containsKey(MessagingHeaders.TRACESTATE);
            assertThat(context.getBean(ObjectMapper.class).readTree(sent.getPayload()).get("trace_id").asString())
                    .isEqualTo(parent.context().traceId());
            assertThat(tracer.currentSpan()).isNull();
        });
    }

    @Test
    void consumerJoinsTheTraceCarriedInTheMessageHeaders() {
        runner.run(context -> {
            InputDestination input = context.getBean(InputDestination.class);
            StreamTestSupport.OrderApplication app = context.getBean(StreamTestSupport.OrderApplication.class);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            Tracer tracer = context.getBean(Tracer.class);
            Propagator propagator = context.getBean(Propagator.class);
            EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("PAID", "mars-cloud-payment-service", "order-8", Map.of());

            // 上游进程用同一套 Propagator 写入的头（Brave 的 W3C 实现还要求 tracestate 里的 b3 条目才会接续）
            Span upstream = tracer.nextSpan().name("upstream").start();
            java.util.Map<String, Object> carried = new java.util.HashMap<>();
            propagator.inject(upstream.context(), carried, java.util.Map::put);
            upstream.end();
            assertThat(carried).containsKey(MessagingHeaders.TRACEPARENT);

            input.send(MessageBuilder.withPayload(mapper.writeValueAsBytes(envelope))
                    .copyHeaders(carried)
                    .setHeader(RocketMqHeaders.RECEIVED_TOPIC, "order-event")
                    .build(), "order-event");

            assertThat(app.received).hasSize(1);
            assertThat(app.received.get(0).traceId()).isEqualTo(upstream.context().traceId());
            assertThat(tracer.currentSpan()).isNull();
        });
    }

    @Test
    void endToEndTraceIdIsIdenticalOnBothSides() {
        runner.run(context -> {
            Tracer tracer = context.getBean(Tracer.class);
            PlainEventPublisher publisher = context.getBean(PlainEventPublisher.class);
            StreamTestSupport.OrderApplication app = context.getBean(StreamTestSupport.OrderApplication.class);
            Span parent = tracer.nextSpan().name("loop").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                publisher.publish("orderLoop-out-0", EventEnvelope.of("CREATED", StreamTestSupport.APPLICATION, "order-9", Map.of()));
            } finally {
                parent.end();
            }
            assertThat(app.received).hasSize(1);
            assertThat(app.received.get(0).traceId()).isEqualTo(parent.context().traceId());
            assertThat(app.received.get(0).message().getPayload().traceId()).isEqualTo(parent.context().traceId());
        });
    }
}
