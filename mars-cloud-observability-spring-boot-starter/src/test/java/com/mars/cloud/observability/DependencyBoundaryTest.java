package com.mars.cloud.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖足迹：可观测性组件会进每一个部署物，它带进来的东西也会进每一个部署物。
 */
class DependencyBoundaryTest {

    /** OTLP 的发送实现用 JDK 自带的 HttpClient。 */
    @Test void otlpUsesTheJdkSender() {
        assertThat(present("io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSender")).isTrue();
    }

    /** 默认的 okhttp 发送器会把 okhttp 与 Kotlin 运行时带进每个部署物，已排除。 */
    @Test void doesNotBringOkHttpOrKotlin() {
        assertThat(present("okhttp3.OkHttpClient")).isFalse();
        assertThat(present("kotlin.Unit")).isFalse();
    }

    /** 指标只走 Prometheus，不再向 OTLP 端点推一份。 */
    @Test void exportsMetricsOnlyThroughPrometheus() {
        assertThat(present("io.micrometer.prometheusmetrics.PrometheusMeterRegistry")).isTrue();
        assertThat(present("io.micrometer.registry.otlp.OtlpMeterRegistry")).isFalse();
    }

    /** 追踪桥是 OpenTelemetry 那套，与设计一致。 */
    @Test void bridgesTracingToOpenTelemetry() {
        assertThat(present("io.micrometer.tracing.otel.bridge.OtelTracer")).isTrue();
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, DependencyBoundaryTest.class.getClassLoader());
            return true;
        }
        catch (Throwable ignored) {
            return false;
        }
    }
}
