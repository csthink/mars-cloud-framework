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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 追踪导出：结束的 span 会以 OTLP 协议送到配置的端点。
 *
 * <p>测试自己起一个回环 HTTP 接收器当作追踪后端，断言收到的请求走的是
 * protobuf over HTTP，正文里带应用名。这样既验证了导出链路，也不依赖真实后端。
 * 采样比例不在这里设置：用例走组件的默认值，Spring Boot 自己的默认值只采样十分之一。
 */
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

    @BeforeAll
    static void startCollector() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", TracingExportTest::record);
        server.start();
    }

    /**
     * 接收器在应用上下文关闭之后才停：上下文里的批量导出器关闭时把尚未发送的 span 发给仍在运行的接收器。
     * 在 {@code @AfterAll} 里停会早于上下文关闭，上下文此后仍按周期导出，连接失败在重试用尽后记一条 ERROR，
     * 构建校验器把它判为未登记的诊断。{@code afterTestClass} 按监听器顺序倒序调用，最高优先级的这个监听器
     * 排在 {@link DirtiesContext} 关闭上下文之后。
     */
    static final class StopCollectorAfterContext implements TestExecutionListener, Ordered {

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterTestClass(TestContext testContext) {
            server.stop(0);
        }
    }

    @DynamicPropertySource
    static void collectorEndpoint(DynamicPropertyRegistry registry) {
        registry.add(MarsObservabilityDefaultsEnvironmentPostProcessor.TRACING_ENDPOINT_PROPERTY,
                () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces");
    }

    /**
     * 阳性对照：Boot 3 的属性名在 Boot 4 已按 error 级废弃，配上去既不报错也不生效。
     * 把它钉在这里，避免有人照旧文档改回旧名后，导出静默失效却没人发现。
     */
    @Test void theLegacyPropertyNameIsNotTheOneWeWrite() {
        assertThat(MarsObservabilityDefaultsEnvironmentPostProcessor.TRACING_ENDPOINT_PROPERTY)
                .isEqualTo("management.opentelemetry.tracing.export.otlp.endpoint")
                .isNotEqualTo("management.otlp.tracing.endpoint");
    }

    /** 组件默认全量采样；生产按流量在配置中心调低。 */
    @Test void samplesEveryTraceByDefault() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
    }

    @Test void finishedSpansReachTheConfiguredEndpoint() {
        Span span = tracer.nextSpan().name("exported-span").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("probe", "tracing-export");
        }
        finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(received).isNotEmpty());

        Received first = received.get(0);
        assertThat(first.path()).isEqualTo("/v1/traces");
        assertThat(first.contentType()).isEqualTo("application/x-protobuf");
        assertThat(new String(first.body(), StandardCharsets.ISO_8859_1))
                .contains("tracing-probe")
                .contains("exported-span");
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
