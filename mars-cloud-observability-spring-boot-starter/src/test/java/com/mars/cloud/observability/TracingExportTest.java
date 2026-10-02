package com.mars.cloud.observability;

import com.mars.cloud.observability.app.plain.PlainProbeApplication;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;


/** 旧导出配置存在时仍可生成与传播上下文，发送与关闭阶段都无导出请求。 */
@SpringBootTest(classes = PlainProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=tracing-probe",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        })
@DirtiesContext
@TestExecutionListeners(listeners = TracingExportTest.StopCollectorAfterContext.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class TracingExportTest {

    private record Received(String path, String contentType, byte[] body) {
    }

    private static HttpServer server;
    private static final List<Received> received = new CopyOnWriteArrayList<>();

    @Autowired Tracer tracer;
    @Autowired Environment environment;
    @Autowired io.micrometer.tracing.propagation.Propagator propagator;
    @Autowired org.springframework.context.ApplicationContext context;

    @BeforeAll
    static void startCollector() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", TracingExportTest::record);
        server.start();
    }

    static final class StopCollectorAfterContext implements TestExecutionListener, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterTestClass(TestContext testContext) {
            try {
                assertThat(received).isEmpty();
            }
            finally {
                server.stop(0);
            }
        }
    }

    @DynamicPropertySource
    static void collectorEndpoint(DynamicPropertyRegistry registry) {
        registry.add(MarsObservabilityDefaultsEnvironmentPostProcessor.TRACING_ENDPOINT_PROPERTY,
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
        registry.add("OTLP_TRACING_ENDPOINT", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
        registry.add("management.tracing.export.otlp.enabled", () -> "true");
    }

    /** 组件默认全量采样；生产按流量在配置中心调低。 */
    @Test void samplesEveryTraceByDefault() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
    }

    @Test void propagatesWithoutExporting() throws Exception {
        assertThat(context.getBeansOfType(io.opentelemetry.sdk.trace.export.SpanExporter.class)).isEmpty();
        Span span = tracer.nextSpan().name("local-span").start();
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            propagator.inject(span.context(), headers, java.util.Map::put);
            assertThat(org.slf4j.MDC.get("traceId")).isEqualTo(span.context().traceId());
        }
        Span child = propagator.extract(headers, java.util.Map::get).name("received-span").start();
        assertThat(child.context().traceId()).isEqualTo(span.context().traceId());
        assertThat(child.context().spanId()).isNotEqualTo(span.context().spanId());
        child.end();
        span.end();
        Thread.sleep(5500);
        assertThat(received).isEmpty();
    }

    private static void record(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            received.add(new Received(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body.readAllBytes()));
        }
        exchange.sendResponseHeaders(200, 0);
        exchange.close();
    }
}
