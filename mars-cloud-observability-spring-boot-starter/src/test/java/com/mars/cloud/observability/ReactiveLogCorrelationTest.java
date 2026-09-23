package com.mars.cloud.observability;

import com.mars.cloud.observability.app.reactive.ReactiveProbeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应式栈的日志关联：请求在 Reactor 线程之间切换之后，日志行仍带入站请求的 traceId。
 *
 * <p>日志的 traceId 取自线程本地的 MDC，而 Boot 默认的
 * {@code spring.reactor.context-propagation=limited} 不会在切换线程时把当前观测恢复进来，
 * 结果是调用链在追踪后端里完整，日志行却丢失 traceId，按 traceId 从日志查不到这个应用的记录。
 */
@SpringBootTest(classes = ReactiveProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=reactive-logging-probe",
                "spring.main.web-application-type=reactive",
                "management.server.port=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret",
                // 理由同 ReactiveManagementSecurityTest：测试 classpath 上有两种 Web 栈。
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.tomcat.autoconfigure.actuate.web.server."
                        + "TomcatReactiveManagementContextAutoConfiguration"
        })
@TestPropertySource(properties = "mars.observability.logging.console-format=ecs")
@ExtendWith({OutputCaptureExtension.class, StructuredLoggingFormatCleanup.class})
class ReactiveLogCorrelationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @LocalServerPort int businessPort;

    @Test void logLineAfterAThreadSwitchCarriesTheIncomingTraceId(CapturedOutput output)
            throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + businessPort + "/business/after-thread-switch"))
                        .header("traceparent", TRACEPARENT)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).isEqualTo("after-thread-switch-ok");

        String line = Arrays.stream(output.toString().split("\n"))
                .filter(candidate -> candidate.startsWith("{")
                        && candidate.contains(ReactiveProbeApplication.AFTER_THREAD_SWITCH_MARKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到 JSON 格式的日志行；实际输出：" + output));
        // 先确认这一行确实写在 Reactor 的 parallel 线程上，否则用例测不到线程切换。
        assertThat(line).contains("\"name\":\"parallel-");
        assertThat(line).contains("\"traceId\":\"" + TRACE_ID + "\"");
    }
}
