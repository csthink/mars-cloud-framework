package com.mars.cloud.rocketmq.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.messaging.MessageHeaders;

import java.util.Map;

/**
 * 用 Micrometer Tracing 的 {@link Tracer} 与 {@link Propagator} 在消息头里写入与还原 W3C Trace Context。
 */
public final class MicrometerMessageTracing implements MessageTracing {

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerMessageTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().traceId();
    }

    @Override
    public Scope startProducer(String topic, Map<String, Object> headers) {
        Span span = tracer.spanBuilder().name("rocketmq publish " + topic).kind(Span.Kind.PRODUCER).start();
        Tracer.SpanInScope inScope = tracer.withSpan(span);
        propagator.inject(span.context(), headers, Map::put);
        return new SpanScope(span, inScope);
    }

    @Override
    public Scope startConsumer(String topic, MessageHeaders headers) {
        Span span = propagator.extract(headers, (carrier, key) -> {
            Object value = carrier.get(key);
            return value == null ? null : value.toString();
        }).name("rocketmq consume " + topic).kind(Span.Kind.CONSUMER).start();
        return new SpanScope(span, tracer.withSpan(span));
    }

    private record SpanScope(Span span, Tracer.SpanInScope inScope) implements Scope {

        @Override
        public void error(Throwable error) {
            if (error != null) {
                span.error(error);
            }
        }

        @Override
        public void close() {
            inScope.close();
            span.end();
        }
    }
}
