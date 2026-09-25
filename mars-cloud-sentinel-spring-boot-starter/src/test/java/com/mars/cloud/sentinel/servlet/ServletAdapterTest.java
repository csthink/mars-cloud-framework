package com.mars.cloud.sentinel.servlet;

import com.mars.cloud.sentinel.rule.RuleType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真端口的 Servlet 服务：资源名是「HTTP 方法:路径模板」，拦截异常到达应用的 {@code @ExceptionHandler}，
 * 不出现 Sentinel 自带的文本。
 */
@SpringBootTest(classes = ServletProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=" + ServletProbeApplication.APPLICATION,
                "spring.main.web-application-type=servlet",
                // 测试 classpath 上也有网关与 Spring Cloud Alibaba 的网关模块：前者在 Servlet 应用里拒绝启动，
                // 后者的环境后处理器把 Servlet 拦截器默认关掉。Servlet 服务的实际 classpath 上两者都没有
                "spring.cloud.gateway.server.webflux.enabled=false",
                "spring.cloud.sentinel.filter.enabled=true"
        })
class ServletAdapterTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @BeforeAll
    static void rules() {
        ServletProbeApplication.RULES
                .put(RuleType.FLOW.dataId(ServletProbeApplication.APPLICATION), """
                        [{"resource":"GET:/probe/{id}","count":0}]
                        """)
                .put(RuleType.DEGRADE.dataId(ServletProbeApplication.APPLICATION), "[]")
                .put(RuleType.PARAM_FLOW.dataId(ServletProbeApplication.APPLICATION), "[]")
                .put(RuleType.SYSTEM.dataId(ServletProbeApplication.APPLICATION), "[]");
    }

    @Test
    void blockedRequestsReachTheApplicationExceptionHandler() throws Exception {
        assertThat(get("/probe/7")).isEqualTo("429 blocked:FlowException:GET:/probe/{id}");
    }

    @Test
    void requestsWithoutRulesPass() throws Exception {
        assertThat(get("/open")).isEqualTo("200 open");
    }

    /** Sentinel 1.8.9 的 Spring MVC 6 适配不区分方法，本组件的拦截器补上方法前缀。 */
    @Test
    void theSamePathWithAnotherMethodIsAnotherResource() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/probe/7"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode() + " " + response.body()).isEqualTo("200 updated 7");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }
}
