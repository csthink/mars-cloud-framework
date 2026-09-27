package com.mars.cloud.job.internal;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 基于 Micrometer Tracing 的实现。
 *
 * <p>{@link Tracer} 在每次执行时经 {@link ObjectProvider} 取得，而不是在装配时判断它是否存在：本组件按类名排序可能先于
 * Spring Boot 的追踪自动配置被处理，装配期的条件判断会误判为没有追踪实现。容器里没有唯一的 Tracer 时不建 span。
 * span 放进当前作用域后，Micrometer 把 traceId 与 spanId 写进日志的 MDC，任务期间的服务日志因此带上 traceId。
 */
public final class MicrometerJobTracing implements JobTracing {

    private final ObjectProvider<Tracer> tracer;

    public MicrometerJobTracing(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public Scope start(String handler, int jobId, long logId) {
        Tracer current = tracer.getIfUnique();
        if (current == null) {
            return Scope.NONE;
        }
        Span span = current.spanBuilder()
                .setNoParent()
                .name("job " + handler)
                .tag("job.id", jobId)
                .tag("job.log_id", logId)
                .start();
        Tracer.SpanInScope inScope = current.withSpan(span);
        return new Scope() {
            @Override
            public String traceId() {
                return span.context().traceId();
            }

            @Override
            public void error(Throwable failure) {
                span.error(failure);
            }

            @Override
            public void close() {
                try {
                    inScope.close();
                } finally {
                    span.end();
                }
            }
        };
    }
}
