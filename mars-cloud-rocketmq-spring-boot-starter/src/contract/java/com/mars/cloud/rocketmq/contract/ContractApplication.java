package com.mars.cloud.rocketmq.contract;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.tracecontext.TraceContextPropagation;
import brave.sampler.Sampler;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.consume.IdempotentEventHandler;
import com.mars.cloud.rocketmq.consume.InMemoryProcessedEventStore;
import com.mars.cloud.rocketmq.consume.ProcessedEventStore;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 契约测试用的最小应用：一个消费函数、一个事务生产 binding、一个普通生产 binding、一个回查检查器、Brave 追踪。
 */
@SpringBootApplication
public class ContractApplication {

    /** 一次投递的记录。 */
    public record Delivery(EventEnvelope<Map<String, Object>> envelope, Map<String, Object> headers,
                           String callerSubject, String traceId, Thread thread, long at) {
    }

    public static final List<Delivery> DELIVERIES = new CopyOnWriteArrayList<>();
    public static final List<String> CHECKED = new CopyOnWriteArrayList<>();
    public static final Map<String, Integer> BUSINESS_EXECUTIONS = new ConcurrentHashMap<>();

    @Bean
    Consumer<Message<EventEnvelope<Map<String, Object>>>> contractEvent(Tracer tracer, IdempotentEventHandler idempotent) {
        return message -> {
            EventEnvelope<Map<String, Object>> envelope = message.getPayload();
            String traceId = tracer.currentSpan() == null ? null : tracer.currentSpan().context().traceId();
            DELIVERIES.add(new Delivery(envelope, Map.copyOf(message.getHeaders()),
                    CallerContextHolder.current().map(c -> c.subject()).orElse(null), traceId,
                    Thread.currentThread(), System.currentTimeMillis()));
            if (envelope.key().startsWith("fail")) {
                throw new IllegalStateException("契约测试的消费失败样本 " + envelope.key());
            }
            idempotent.handle("mars-cloud-contract-service-contract-event", message,
                    e -> BUSINESS_EXECUTIONS.merge(e.eventId(), 1, Integer::sum));
        };
    }

    @Bean
    ProcessedEventStore processedEventStore() {
        return new InMemoryProcessedEventStore();
    }

    @Bean
    TransactionStateChecker contractChecker() {
        return new TransactionStateChecker() {
            @Override
            public boolean supports(String topic) {
                return "contract-event".equals(topic);
            }

            @Override
            public LocalTransactionState check(EventEnvelope<?> envelope, MessageExt raw) {
                CHECKED.add(envelope.key());
                return envelope.key().startsWith("crash-commit") ? LocalTransactionState.COMMIT_MESSAGE
                        : LocalTransactionState.ROLLBACK_MESSAGE;
            }
        };
    }

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
