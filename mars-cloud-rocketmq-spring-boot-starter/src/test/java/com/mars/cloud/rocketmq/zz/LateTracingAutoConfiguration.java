package com.mars.cloud.rocketmq.zz;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.tracecontext.TraceContextPropagation;
import brave.sampler.Sampler;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 按类名排在本 starter 自动配置之后的 tracing 自动配置，模拟 Spring Boot 真实的自动配置顺序：
 * starter 的 bean 定义先登记，Tracer 与 Propagator 的定义后登记。
 */
@AutoConfiguration
public class LateTracingAutoConfiguration {

    @Bean(destroyMethod = "close")
    Tracing lateBraveTracing() {
        return Tracing.newBuilder()
                .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder().build())
                .propagationFactory(TraceContextPropagation.FACTORY)
                .traceId128Bit(true)
                .supportsJoin(false)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .build();
    }

    @Bean
    Tracer lateTracer(Tracing tracing) {
        return new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
    }

    @Bean
    Propagator latePropagator(Tracing tracing) {
        return new BravePropagator(tracing);
    }
}
