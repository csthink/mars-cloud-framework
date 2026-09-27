package com.mars.cloud.job.internal;

import com.mars.cloud.job.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorHttpServerTest {

    static final String TOKEN_VALUE = "test-access-token-0123456789";

    public static class Jobs {
        @JobHandler("noop")
        public void noop() {
        }
    }

    @TempDir
    Path logDirectory;

    private final List<Protocol.CallbackRequest> results = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private JobDispatcher dispatcher;
    private ExecutorHttpServer server;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("jobs", new RootBeanDefinition(Jobs.class));
        JobHandlerRegistry registry = new JobHandlerRegistry();
        registry.setBeanFactory(factory);
        registry.afterSingletonsInstantiated();
        JobLogFiles logs = new JobLogFiles(logDirectory, Clock.systemDefaultZone());
        logs.initialize();
        dispatcher = new JobDispatcher(registry, logs, results::add, JobTracing.NONE, Duration.ofSeconds(90));
        server = new ExecutorHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), TOKEN_VALUE, dispatcher);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        dispatcher.shutdown(Duration.ofSeconds(1));
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header(Protocol.ACCESS_TOKEN_HEADER, token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Protocol.Response post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = send("POST", path, token, body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json;charset=UTF-8");
        return Protocol.read(response.body().getBytes(StandardCharsets.UTF_8), Protocol.Response.class);
    }

    @Test
    void theServerListensOnlyOnTheConfiguredAddress() {
        assertThat(server.port()).isPositive();
        assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();

        ExecutorHttpServer wildcard = new ExecutorHttpServer(new InetSocketAddress((InetAddress) null, 0), TOKEN_VALUE, dispatcher);
        wildcard.start();
        try {
            assertThat(wildcard.address().getAddress().isAnyLocalAddress()).isTrue();
        } finally {
            wildcard.stop();
        }
    }

    @Test
    void everyRequestNeedsTheAccessToken() throws Exception {
        assertThat(post("/beat", null, "{}").msg()).isEqualTo(ExecutorHttpServer.WRONG_TOKEN);
        assertThat(post("/beat", "wrong-token-0123456789", "{}").msg()).isEqualTo(ExecutorHttpServer.WRONG_TOKEN);
        assertThat(post("/run", "wrong-token-0123456789",
                "{\"jobId\":1,\"executorHandler\":\"noop\",\"glueType\":\"BEAN\",\"logId\":5}"))
                .extracting(Protocol.Response::code, Protocol.Response::msg).containsExactly(Protocol.FAIL, ExecutorHttpServer.WRONG_TOKEN);
        assertThat(results).isEmpty();
        assertThat(post("/beat", TOKEN_VALUE, "{}").succeeded()).isTrue();
    }

    @Test
    void aRequestWithoutTheTokenIsAnsweredWithoutReadingItsBody() throws Exception {
        // 请求头声明 8 MiB 正文，只发出 1 字节：先读正文的实现会一直等下去，读超时让用例失败。
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /run HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "
                    + 8 * ExecutorHttpServer.MAX_BODY_BYTES + "\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            while (!response.toString().contains(ExecutorHttpServer.WRONG_TOKEN) && (read = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            assertThat(response.toString()).startsWith("HTTP/1.1 200").contains(ExecutorHttpServer.WRONG_TOKEN);
        }
    }

    @Test
    void theFiveProtocolPathsAreServed() throws Exception {
        long now = System.currentTimeMillis();
        assertThat(post("/idleBeat", TOKEN_VALUE, "{\"jobId\":1}").succeeded()).isTrue();
        assertThat(post("/run", TOKEN_VALUE, "{\"jobId\":1,\"executorHandler\":\"noop\",\"executorBlockStrategy\":\"SERIAL_EXECUTION\","
                + "\"glueType\":\"BEAN\",\"logId\":5,\"logDateTime\":" + now + ",\"broadcastTotal\":1}").succeeded()).isTrue();
        assertThat(post("/kill", TOKEN_VALUE, "{\"jobId\":1}").succeeded()).isTrue();
        Protocol.Response log = post("/log", TOKEN_VALUE, "{\"logDateTim\":" + now + ",\"logId\":5,\"fromLineNum\":1}");
        assertThat(log.succeeded()).isTrue();
        assertThat(log.data()).isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) log.data()).keySet()).map(Object::toString)
                .containsExactlyInAnyOrder("fromLineNum", "toLineNum", "logContent", "isEnd");
    }

    @Test
    void malformedBodiesGetAReadableFailure() throws Exception {
        assertThat(post("/run", TOKEN_VALUE, "not json").msg()).startsWith("请求体无法解析为 TriggerRequest");
    }

    @Test
    void otherMethodsPathsAndOversizedBodiesAreRefused() throws Exception {
        HttpResponse<String> get = send("GET", "/beat", TOKEN_VALUE, null);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).hasValue("POST");
        assertThat(send("POST", "/beat/", TOKEN_VALUE, "{}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/actuator/health", TOKEN_VALUE, "{}").statusCode()).isEqualTo(404);
        String oversized = "{\"jobId\":1,\"padding\":\"" + "x".repeat(ExecutorHttpServer.MAX_BODY_BYTES) + "\"}";
        assertThat(send("POST", "/run", TOKEN_VALUE, oversized).statusCode()).isEqualTo(413);
    }

    @Test
    void anOccupiedPortFailsStartupInsteadOfBeingIgnored() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            ExecutorHttpServer second = new ExecutorHttpServer(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), occupied.getLocalPort()), TOKEN_VALUE, dispatcher);
            assertThatThrownBy(second::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("执行器端口绑定失败：127.0.0.1:" + occupied.getLocalPort());
        }
    }
}
