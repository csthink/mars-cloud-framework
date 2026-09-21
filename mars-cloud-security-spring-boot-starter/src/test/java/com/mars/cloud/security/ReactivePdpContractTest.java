package com.mars.cloud.security;

import com.mars.cloud.security.autoconfigure.*;
import com.mars.cloud.security.reactive.WebClientPdpClient;
import com.mars.cloud.security.test.TestIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class ReactivePdpContractTest {
    private TestIdentityProvider issuer;
    private HttpServer server;
    private ExecutorService executor;
    private WebClientPdpClient client;
    private JwtAuthenticationToken authentication;
    private String rawToken;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private final AtomicReference<String> bearer = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delay;
    private volatile String response = "{\"success\":true,\"result\":{\"decision\":\"allow\",\"reason_code\":\"granted\",\"decision_id\":\"id-1\"}}";
    @BeforeEach void start() throws Exception {
        issuer = new TestIdentityProvider();
        var properties = new SecurityProperties(); var trust = new JwtTrustProperties(); trust.setIssuerUri(issuer.issuer());
        trust.setJwkSetUri(issuer.jwksUri()); properties.setAudience("sample");
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        rawToken = issuer.token("alice", "sample", "upms");
        var jwt = new ServletSecurityAutoConfiguration().marsJwtDecoder(properties, trust, env).decode(rawToken);
        authentication = new JwtAuthenticationToken(jwt, List.of());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
        server.createContext(PdpProtocol.PATH, exchange -> {
            calls.incrementAndGet(); bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/leak");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/leak", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        client = new WebClientPdpClient(WebClient.builder().clientConnector(ReactiveSecurityAutoConfiguration.connector())
                .filter((request, next) -> next.exchange(ClientRequest.from(request)
                        .url(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + request.url().getPath())).build())));
    }
    @AfterEach void close() { if (server != null) server.stop(0); if (executor != null) executor.close(); if (issuer != null) issuer.close(); }
    private Mono<PdpDecision> decide() {
        return client.decide(AuthenticatedCaller.from(authentication), "view", "resource")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
    @Test void propagatesOnlyCurrentValidatedTokenAndStrictDecisionBody() {
        assertThat(decide().block(Duration.ofSeconds(6)).allowed()).isTrue();
        assertThat(bearer.get()).isEqualTo("Bearer " + rawToken);
        assertThat(body.get()).contains("\"caller_id\":\"alice\"", "\"action\":\"view\"");
        assertThat(calls).hasValue(1);
        response = response.replace("allow", "deny");
        assertThat(decide().block(Duration.ofSeconds(6)).allowed()).isFalse();
    }
    @ParameterizedTest @CsvSource({"302,62005", "401,62005", "403,62005", "500,62005", "503,62004"})
    void statusFailuresNeverRetryFollowRedirectOrReturnAllow(int httpStatus, int expectedCode) {
        status = httpStatus;
        assertThatThrownBy(() -> decide().block(Duration.ofSeconds(6)))
                .isInstanceOfSatisfying(SecurityFailure.class, failure -> assertThat(failure.code().getCode()).isEqualTo(expectedCode));
        assertThat(calls).hasValue(1); assertThat(redirects).hasValue(0);
    }
    @Test void rejectsEveryMalformedProtocolShape() {
        for (String value : List.of("", "{", "{}", "{\"result\":{}}", "{\"success\":false}",
                response.replace("true", "1"), response.replace("allow", "other"),
                response.replace("\"granted\"", "1"), response.replace("\"id-1\"", "null"))) {
            response = value;
            assertThatThrownBy(() -> decide().block(Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(SecurityFailure.class, failure -> assertThat(failure.code()).isEqualTo(SecurityErrorCode.PDP_PROTOCOL_ERROR));
        }
    }
    @Test void requiresAuthenticationAndMatchingCallerBeforeHttp() {
        assertThatThrownBy(() -> client.decide(AuthenticatedCaller.from(authentication), "view", "resource").block())
                .isInstanceOf(SecurityFailure.class);
        var forged = new com.mars.cloud.common.context.CallerContext("eve", "test-client", "default");
        assertThatThrownBy(() -> client.decide(forged, "view", "resource")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block())
                .isInstanceOfSatisfying(SecurityFailure.class, failure -> assertThat(failure.code()).isEqualTo(SecurityErrorCode.CALLER_MISMATCH));
        assertThat(calls).hasValue(0);
    }
    @Test void timesOutWithoutRetryAndSanitizesTransportFailure() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("reactor.netty.http.client.HttpClientConnect");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        boolean additive = logger.isAdditive();
        logger.addAppender(captured); logger.setAdditive(false);
        try {
            delay = 3400;
            assertThatThrownBy(() -> decide().block(Duration.ofSeconds(6)))
                    .isInstanceOfSatisfying(SecurityFailure.class, failure -> {
                        assertThat(failure.code()).isEqualTo(SecurityErrorCode.PDP_UNAVAILABLE);
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.getMessage()).doesNotContain(rawToken);
                    });
            assertThat(calls).hasValue(1);
            assertThat(captured.list).hasSize(1);
            assertThat(captured.list.getFirst().getThrowableProxy().getClassName()).isEqualTo("io.netty.handler.timeout.ReadTimeoutException");
            assertThat(captured.list.getFirst().getFormattedMessage()).doesNotContain(rawToken);
        } finally {
            logger.detachAppender(captured); logger.setAdditive(additive); captured.stop();
        }
    }
}
