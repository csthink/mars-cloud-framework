package com.mars.cloud.job.autoconfigure;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import com.mars.cloud.job.JobContext;
import com.mars.cloud.job.JobHandler;
import com.mars.cloud.job.internal.FakeAdmin;
import com.mars.cloud.job.internal.JobExecutor;
import com.mars.cloud.job.internal.JobHandlerRegistry;
import com.mars.cloud.job.internal.Protocol;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MarsJobAutoConfigurationTest {

    static final String TOKEN_VALUE = "test-access-token-0123456789";
    static final String ACCESS_TOKEN_PROPERTY = "mars.job.access-token";

    public static class SampleJobs {
        final List<String> params = new CopyOnWriteArrayList<>();

        @JobHandler("autoconfigured")
        public void run(JobContext context) {
            params.add(context.param());
            context.log("autoconfigured job ran with {}", context.param());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Jobs {
        @Bean
        SampleJobs sampleJobs() {
            return new SampleJobs();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BraveConfiguration {
        static final List<MutableSpan> FINISHED = new CopyOnWriteArrayList<>();

        @Bean(destroyMethod = "close")
        Tracing tracing() {
            return Tracing.newBuilder()
                    .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .addSpanHandler(new SpanHandler() {
                        @Override
                        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                            FINISHED.add(span);
                            return true;
                        }
                    })
                    .build();
        }

        @Bean
        Tracer tracer(Tracing tracing) {
            return new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
        }
    }

    @TempDir
    Path logDirectory;

    private FakeAdmin admin;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        admin = new FakeAdmin();
        BraveConfiguration.FINISHED.clear();
    }

    @AfterEach
    void tearDown() {
        admin.close();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsJobAutoConfiguration.class))
                .withUserConfiguration(Jobs.class)
                .withPropertyValues("spring.application.name=autoconfig-app", "server.address=127.0.0.1",
                        "mars.job.executor.port=0", "mars.job.admin.addresses=" + admin.uri(),
                        ACCESS_TOKEN_PROPERTY + "=" + TOKEN_VALUE, "mars.job.executor.log-path=" + logDirectory);
    }

    private Protocol.Response run(JobExecutor executor, int jobId, long logId, long logDateTime, String param) throws Exception {
        String body = "{\"jobId\":" + jobId + ",\"executorHandler\":\"autoconfigured\",\"executorParams\":\"" + param + "\","
                + "\"executorBlockStrategy\":\"SERIAL_EXECUTION\",\"glueType\":\"BEAN\",\"logId\":" + logId
                + ",\"logDateTime\":" + logDateTime + ",\"broadcastTotal\":1}";
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + executor.port() + "/run"))
                .header(Protocol.ACCESS_TOKEN_HEADER, TOKEN_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        return Protocol.read(response.body(), Protocol.Response.class);
    }

    private String jobLog(long logId) throws Exception {
        try (Stream<Path> files = Files.walk(logDirectory)) {
            Path file = files.filter(path -> path.getFileName().toString().equals(logId + ".log")).findFirst().orElseThrow();
            return Files.readString(file);
        }
    }

    @Test
    void disabledMeansNothingIsAssembled() {
        runner().withPropertyValues("mars.job.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JobExecutor.class);
            assertThat(context).doesNotHaveBean(JobHandlerRegistry.class);
            assertThat(context).doesNotHaveBean(JobConventionVerifier.class);
        });
        assertThat(admin.received()).isEmpty();
    }

    @Test
    void theExecutorRegistersRunsHandlersAndDeregistersOnClose() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            JobExecutor executor = context.getBean(JobExecutor.class);
            assertThat(executor.isRunning()).isTrue();
            String address = "http://127.0.0.1:" + executor.port() + "/";
            assertThat(executor.registeredAddress()).isEqualTo(address);
            await().atMost(Duration.ofSeconds(5)).until(() -> !admin.received("/api/registry").isEmpty());
            assertThat(admin.received("/api/registry").getFirst().body())
                    .isEqualTo("{\"registryGroup\":\"EXECUTOR\",\"registryKey\":\"autoconfig-app\",\"registryValue\":\"" + address + "\"}");

            long now = System.currentTimeMillis();
            assertThat(run(executor, 31, 3101, now, "p31").succeeded()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> admin.callbacks().stream().anyMatch(r -> r.logId() == 3101));
            assertThat(admin.callbacks()).contains(new Protocol.CallbackRequest(3101, now, Protocol.SUCCESS, null));
            assertThat(context.getBean(SampleJobs.class).params).containsExactly("p31");
            assertThat(jobLog(3101)).contains("autoconfigured job ran with p31").contains("任务执行成功");
        });
        assertThat(admin.received("/api/registryRemove")).hasSize(1);
    }

    @Test
    void eachRunIsTheRootOfItsOwnTrace() {
        runner().withUserConfiguration(BraveConfiguration.class).run(context -> {
            JobExecutor executor = context.getBean(JobExecutor.class);
            run(executor, 32, 3201, System.currentTimeMillis(), "traced");
            await().atMost(Duration.ofSeconds(5)).until(() -> admin.callbacks().stream().anyMatch(r -> r.logId() == 3201));
            MutableSpan span = BraveConfiguration.FINISHED.stream().filter(s -> "job autoconfigured".equals(s.name()))
                    .findFirst().orElseThrow();
            assertThat(span.parentId()).isNull();
            assertThat(span.tag("job.id")).isEqualTo("32");
            assertThat(span.tag("job.log_id")).isEqualTo("3201");
            assertThat(jobLog(3201)).contains("traceId=" + span.traceId());
        });
    }

    @Test
    void withoutATracerNoTraceIdIsWritten() {
        runner().run(context -> {
            run(context.getBean(JobExecutor.class), 33, 3301, System.currentTimeMillis(), "plain");
            await().atMost(Duration.ofSeconds(5)).until(() -> admin.callbacks().stream().anyMatch(r -> r.logId() == 3301));
            assertThat(jobLog(3301)).contains("开始执行任务 autoconfigured，参数=plain").doesNotContain("traceId");
        });
    }

    @Test
    void lazyInitializationStillVerifiesRegistersAndStarts() {
        runner().withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JobExecutor.class).isRunning()).isTrue();
                    assertThat(context.getBean(JobHandlerRegistry.class).names()).containsExactly("autoconfigured");
                });
    }

    @Test
    void invalidSettingsFailStartup() {
        runner().withPropertyValues(ACCESS_TOKEN_PROPERTY + "=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("缺少访问令牌：设置环境变量 MARS_JOB_ACCESS_TOKEN");
        });
    }

    @Test
    void anOccupiedExecutorPortFailsStartup() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            runner().withPropertyValues("mars.job.executor.port=" + occupied.getLocalPort()).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("执行器端口绑定失败：127.0.0.1:" + occupied.getLocalPort());
            });
        }
    }
}
