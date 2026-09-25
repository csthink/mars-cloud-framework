package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.mars.cloud.sentinel.internal.MicrometerBlockedRequestRecorder;
import com.mars.cloud.sentinel.internal.MicrometerRuleUpdateRecorder;
import com.mars.cloud.sentinel.rule.RuleType;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.Ordered;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真端口的网关：按路由加客户端地址限流、按 API 分组限流，计数用的是交换属性里的地址而不是 TCP 对端；
 * 拦截异常到达应用自己的错误处理；地址缺失时不回退；坏规则保留上一批。
 */
@SpringBootTest(classes = GatewayProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=" + GatewayProbeApplication.APPLICATION,
                "spring.main.web-application-type=reactive",
                "mars.sentinel.gateway.client-ip-attribute=" + GatewayProbeApplication.CLIENT_IP_ATTRIBUTE,
                "spring.cloud.gateway.server.webflux.routes[0].id=probe",
                "spring.cloud.gateway.server.webflux.routes[0].uri=forward:/stub",
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/probe/**",
                "spring.cloud.gateway.server.webflux.routes[1].id=callbacks",
                "spring.cloud.gateway.server.webflux.routes[1].uri=forward:/stub",
                "spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/callbacks/**"
        })
class GatewayAdapterTest {

    private static final String GROUPS = RuleType.GATEWAY_API_GROUP.dataId(GatewayProbeApplication.APPLICATION);
    private static final String FLOW = RuleType.GATEWAY_FLOW.dataId(GatewayProbeApplication.APPLICATION);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    SentinelGatewayFilter filter;

    @Autowired
    MeterRegistry registry;

    @BeforeAll
    static void rules() {
        GatewayProbeApplication.RULES
                .put(GROUPS, """
                        [{"apiName":"callback-group","predicateItems":[{"pattern":"/callbacks/**","matchStrategy":1}]}]
                        """)
                .put(FLOW, """
                        [{"resource":"probe","count":1,"intervalSec":60,"paramItem":{"parseStrategy":0}},
                         {"resource":"callback-group","resourceMode":1,"count":1,"intervalSec":60}]
                        """);
    }

    @Test
    void limitsEachClientAddressSeparatelyOnTheRoute() throws Exception {
        // 冷启动的第一次请求可能超过规则窗口，先用别的地址预热
        get("/probe/warm", "10.0.0.250");

        assertThat(get("/probe/a", "10.0.0.1")).isEqualTo("200 ok");
        assertThat(get("/probe/a", "10.0.0.1")).isEqualTo("429 blocked:ParamFlowException");
        assertThat(get("/probe/a", "10.0.0.2")).isEqualTo("200 ok");
        assertThat(registry.get(MicrometerBlockedRequestRecorder.BLOCKED).tag("resource", "probe").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void limitsTheApiGroupAcrossClientAddresses() throws Exception {
        assertThat(get("/callbacks/pay", "10.0.1.1")).isEqualTo("200 ok");
        assertThat(get("/callbacks/pay", "10.0.1.2")).isEqualTo("429 blocked:ParamFlowException");
    }

    @Test
    void refusesToFallBackToThePeerAddress() throws Exception {
        assertThat(get("/probe/no-client", null))
                .startsWith("500 error:")
                .contains(GatewayProbeApplication.CLIENT_IP_ATTRIBUTE);
    }

    @Test
    void runsBeforeTheOtherGlobalFilters() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void keepsThePreviousRulesWhenAnUpdateReferencesAnUnknownRoute() {
        int before = GatewayRuleManager.getRules().size();
        GatewayProbeApplication.RULES.publish(FLOW, "[{\"resource\":\"prob\",\"count\":1}]");

        assertThat(GatewayRuleManager.getRules()).hasSize(before)
                .extracting(GatewayFlowRule::getResource).contains("probe");
        assertThat(registry.get(MicrometerRuleUpdateRecorder.SOURCE_VALID).tag("rule_type", "gw-flow").gauge().value())
                .isZero();
    }

    private String get(String path, String client) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (client != null) {
            request.header(GatewayProbeApplication.CLIENT_IP_HEADER, client);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }
}
