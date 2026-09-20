package com.mars.cloud.feign;

import brave.Tracing;
import brave.handler.SpanHandler;
import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.tracecontext.TraceContextPropagation;
import brave.sampler.Sampler;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import com.mars.cloud.feign.internal.DownstreamFailureMapperRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import feign.Response;
import feign.codec.StringDecoder;
import feign.micrometer.MicrometerObservationCapability;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FeignTracingContractTest {

    @Test
    void micrometerCapabilityPropagatesW3cTraceparent() throws Exception {
        CurrentTraceContext braveContext = ThreadLocalCurrentTraceContext.newBuilder().build();
        try (Tracing braveTracing = Tracing.newBuilder()
                .currentTraceContext(braveContext)
                .propagationFactory(TraceContextPropagation.FACTORY)
                .traceId128Bit(true)
                .supportsJoin(false)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .addSpanHandler(new SpanHandler() { })
                .build()) {
            Tracer tracer = new BraveTracer(
                    braveTracing.tracer(), new BraveCurrentTraceContext(braveContext));
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(
                    new PropagatingSenderTracingObservationHandler<>(
                            tracer, new BravePropagator(braveTracing)));

            AtomicReference<Request> captured = new AtomicReference<>();
            Client delegate = (request, options) -> {
                captured.set(request);
                return Response.builder()
                        .status(200)
                        .reason("OK")
                        .request(request)
                        .headers(Map.of())
                        .body("{\"success\":true}".getBytes(StandardCharsets.UTF_8))
                        .build();
            };
            TraceClient client = Feign.builder()
                    .client(delegate)
                    .retryer(Retryer.NEVER_RETRY)
                    .options(new Request.Options(1000, 3000))
                    .addCapability(new MarsFeignCapability(
                            new DownstreamFailureMapperRegistry(List.of()), JsonMapper.builder().build()))
                    .decoder(new StringDecoder())
                    .addCapability(new MicrometerObservationCapability(registry))
                    .target(TraceClient.class, "http://orders");
            Span parent = tracer.nextSpan().name("contract-parent").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                assertThat(client.get()).isEqualTo("{\"success\":true}");
            }
            finally {
                parent.end();
            }

            String traceparent = header(captured.get(), "traceparent");
            assertThat(traceparent)
                    .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")
                    .contains(parent.context().traceId());
        }
    }

    private static String header(Request request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .findFirst()
                .orElse(null);
    }

    private interface TraceClient {

        @RequestLine("GET /resource")
        String get();
    }
}
