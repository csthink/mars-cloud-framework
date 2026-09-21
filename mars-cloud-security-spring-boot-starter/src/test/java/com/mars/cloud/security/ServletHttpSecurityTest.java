package com.mars.cloud.security;

import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.security.test.TestIdentityProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

class ServletHttpSecurityTest extends HttpSecurityContract {
    protected Class<?> application() { return App.class; }
    protected WebApplicationType stack() { return WebApplicationType.SERVLET; }
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @Import(Endpoints.class)
    static class App {
        @Bean PdpClient pdp() { return (caller, action, resource) -> decision(action); }
    }
    @RestController
    public static class Endpoints {
        @GetMapping("/me") public Map<String, Object> me(Authentication authentication) {
            return Map.of("subject", CallerContextHolder.current().orElseThrow().subject(),
                    "authorities", authentication.getAuthorities().stream().map(Object::toString).toList());
        }
        @GetMapping("/method/{action}") @PreAuthorize("@marsAuthorization.allowed(#action,'resource')")
        public Map<String, Boolean> guarded(@PathVariable String action) {
            INVOCATIONS.incrementAndGet(); return Map.of("allowed", true);
        }
        @GetMapping("/async") public Callable<Map<String, String>> async() {
            return () -> Map.of("subject", CallerContextHolder.current().orElseThrow().subject());
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class HttpSecurityContract {
    protected static final AtomicInteger INVOCATIONS = new AtomicInteger();
    private TestIdentityProvider issuer;
    private ConfigurableApplicationContext context;
    private String base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    protected abstract Class<?> application();
    protected abstract WebApplicationType stack();
    @BeforeAll void start() {
        issuer = new TestIdentityProvider();
        context = new SpringApplicationBuilder(application()).web(stack()).profiles("test").properties(Map.of(
                "server.port", "0", "spring.main.banner-mode", "off",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer.issuer(), "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", issuer.jwksUri(),
                "mars.security.audience", "sample")).run();
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }
    @AfterAll void stop() { if (context != null) context.close(); if (issuer != null) issuer.close(); }
    protected static PdpDecision decision(String action) {
        return switch (action) {
            case "unavailable" -> throw new SecurityFailure(SecurityErrorCode.PDP_UNAVAILABLE);
            case "protocol" -> throw new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR);
            default -> new PdpDecision(!action.equals("deny"), "reason", "id");
        };
    }
    protected String signedToken() { return issuer.token("alice", "sample"); }
    protected HttpResponse<String> get(String path, String token, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (headers.length > 0) request.headers(headers);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) { return JsonMapper.builder().build().readTree(response.body()); }
    @Test void rejectsMissingInvalidAndQueryTokens() throws Exception {
        for (String path : List.of("/me", org.springframework.web.util.UriComponentsBuilder.fromPath("/me")
                .queryParam("access_token", issuer.token("alice", "sample")).build().toUriString())) {
            var response = get(path, null, "X-Mars-Subject", "forged");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json(response).path("code").stringValue()).isEqualTo("62001");
            assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        }
        var response = get("/me", "sentinel-not-a-jwt");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json(response).path("code").stringValue()).isEqualTo("62002");
        assertThat(response.body()).doesNotContain("sentinel", "Authorization");
    }
    @Test void identityComesFromVerifiedJwtAndPrivilegeClaimsGiveNoAuthorities() throws Exception {
        var claims = issuer.claims("alice", "sample"); claims.put("roles", List.of("admin")); claims.put("scope", "all");
        var response = get("/me", issuer.sign(claims), "X-Mars-Subject", "forged", "X-Mars-Tenant-Id", "forged");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("subject").stringValue()).isEqualTo("alice");
        assertThat(json(response).path("authorities").toString()).doesNotContain("ROLE_", "SCOPE_", "admin", "all");
        assertThat(CallerContextHolder.current()).isEmpty();
    }
    @ParameterizedTest @CsvSource({"allow,200,", "deny,403,62003", "unavailable,503,62004", "protocol,502,62005"})
    void realMethodProxyPreventsDeniedExecution(String action, int status, String code) throws Exception {
        int before = INVOCATIONS.get();
        var response = get("/method/" + action, issuer.token("alice", "sample"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (code != null) assertThat(json(response).path("code").stringValue()).isEqualTo(code);
        assertThat(INVOCATIONS.get() - before).isEqualTo(status == 200 ? 1 : 0);
        assertThat(response.body()).doesNotContain("Authorization", "Exception", "headers");
    }
    @Test void unknownKeyAndUnavailableJwksFailClosed() throws Exception {
        issuer.rotate(false);
        String token = issuer.token("alice", "sample");
        issuer.jwksStatus(503);
        try {
            var response = get("/me", token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
            assertThat(json(response).path("code").stringValue()).isEqualTo("62002");
            assertThat(response.body()).doesNotContain(token, "Exception", "headers");
        } finally { issuer.jwksStatus(200); }
    }
    @Test void concurrentRequestsAndThreadSwitchKeepCallerIsolated() {
        var jobs = java.util.stream.IntStream.range(0, 12).mapToObj(index -> {
            String subject = "caller-" + index;
            var request = HttpRequest.newBuilder(URI.create(base + "/async"))
                    .header("Authorization", "Bearer " + issuer.token(subject, "sample")).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(json(response).path("subject").stringValue()).isEqualTo(subject);
            });
        }).toList();
        jobs.forEach(java.util.concurrent.CompletableFuture::join);
        assertThat(CallerContextHolder.current()).isEmpty();
    }
}
