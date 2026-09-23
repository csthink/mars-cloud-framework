package com.mars.cloud.observability;

import com.mars.cloud.observability.app.plain.PlainProbeApplication;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化日志与链路标识的关联：日志行必须带当前 span 的 traceId，
 * 否则按 traceId 从日志跳到调用链这条路走不通。
 *
 * <p>字段形态按 Boot 4.0.8 的实测结果断言：{@code traceId} 与 {@code spanId} 是顶层平铺字段，
 * {@code service} 与 {@code ecs} 是嵌套对象。不要写成带点的字面量。
 */
@SpringBootTest(classes = PlainProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=logging-probe",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        })
@TestPropertySource(properties = "mars.observability.logging.console-format=ecs")
@ExtendWith({OutputCaptureExtension.class, StructuredLoggingFormatCleanup.class})
class StructuredLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTest.class);
    private static final String MARKER = "structured-logging-marker";

    @Autowired Tracer tracer;

    @Test void logLineCarriesTheCurrentTraceIdentifiers(CapturedOutput output) {
        Span span = tracer.nextSpan().name("logging-probe").start();
        String traceId;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            traceId = tracer.currentSpan().context().traceId();
            log.info(MARKER);
        }
        finally {
            span.end();
        }

        String line = jsonLineContaining(output, MARKER);
        assertThat(line)
                .contains("\"traceId\":\"" + traceId + "\"")
                .contains("\"spanId\":")
                .contains("\"service\":{\"name\":\"logging-probe\"")
                .contains("\"ecs\":{\"version\":")
                .contains("\"message\":\"" + MARKER + "\"");
        // 这两个带点的字面量是 Elastic Common Schema 的习惯写法，但格式化器写的是嵌套对象。
        // 钉住这一点，避免后来者按习惯去断言一个永远不出现的字段名。
        assertThat(line).doesNotContain("\"trace.id\"").doesNotContain("\"service.name\"");
    }

    private static String jsonLineContaining(CapturedOutput output, String marker) {
        return Arrays.stream(output.toString().split("\n"))
                .filter(line -> line.startsWith("{") && line.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到 JSON 格式的日志行；实际输出：" + output));
    }
}
