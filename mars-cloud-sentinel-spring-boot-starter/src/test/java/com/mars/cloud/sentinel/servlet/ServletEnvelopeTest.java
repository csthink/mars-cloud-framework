package com.mars.cloud.sentinel.servlet;

import com.mars.cloud.sentinel.rule.RuleType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The Sentinel interceptor and the MVC advice run together on a real HTTP port. */
@SpringBootTest(classes = ServletEnvelopeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=" + ServletEnvelopeApplication.APPLICATION,
                "spring.main.web-application-type=servlet",
                "spring.cloud.gateway.server.webflux.enabled=false",
                "spring.cloud.sentinel.filter.enabled=true",
                "spring.messages.basename=i18n/error-code",
                "mars.error-code.framework-layers[0]=common",
                "mars.error-code.framework-layers[1]=mvc"
        })
class ServletEnvelopeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @BeforeAll
    static void rules() {
        ServletEnvelopeApplication.RULES
                .put(RuleType.FLOW.dataId(ServletEnvelopeApplication.APPLICATION),
                        "[{\"resource\":\"GET:/probe/{id}\",\"count\":0}]")
                .put(RuleType.DEGRADE.dataId(ServletEnvelopeApplication.APPLICATION), "[]")
                .put(RuleType.PARAM_FLOW.dataId(ServletEnvelopeApplication.APPLICATION), "[]")
                .put(RuleType.SYSTEM.dataId(ServletEnvelopeApplication.APPLICATION), "[]");
    }

    @Test
    void blockedRequestUsesLocalizedMvcEnvelope() throws Exception {
        assertBlocked("zh-CN", "请求过于频繁，请稍后再试");
        assertBlocked("en-US", "Too many requests, please try again later");
    }

    @Test
    void unrelatedRequestPasses() throws Exception {
        HttpResponse<String> response = get("/open", "en-US");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isTrue();
    }

    private void assertBlocked(String language, String message) throws Exception {
        HttpResponse<String> response = get("/probe/7", language);
        assertThat(response.statusCode()).isEqualTo(429);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asString()).isEqualTo("61006");
        assertThat(body.get("message").asString()).isEqualTo(message);
        assertThat(body.get("result")).isNull();
        assertThat(response.body()).doesNotContain("FlowException", "GET:/probe/{id}");
    }

    private HttpResponse<String> get(String path, String language) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept-Language", language).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
