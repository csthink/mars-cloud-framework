package com.mars.cloud.sentinel.feign;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureKind;
import com.mars.cloud.feign.DownstreamFailureMapper;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleType;
import feign.Client;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 经 Spring Cloud OpenFeign 装配的真实客户端：资源名按客户端，5xx 与传输异常计入资源异常、4xx 不计入；
 * 被拦截时调用方的映射器收到「下游不可用」，原因是 {@link SentinelBlockedCallException}，说明 Sentinel 的包装
 * 位于 feign 组件的失败映射里层。下游用一个按路径返回状态码的 {@link Client} bean 代替；有了它，
 * 负载均衡客户端不再装配，客户端按服务名直接调用这个 bean。
 */
@SpringBootTest(classes = FeignResourceTest.FeignProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=" + FeignResourceTest.APPLICATION,
                "spring.cloud.gateway.server.webflux.enabled=false"
        })
class FeignResourceTest {

    static final String APPLICATION = "feign-probe";
    static final String CLIENT = "probe-downstream";
    static final String RESOURCE = "feign:" + CLIENT;
    static final InMemoryRuleConfigSource RULES = new InMemoryRuleConfigSource();
    static final AtomicReference<DownstreamFailure> LAST_FAILURE = new AtomicReference<>();

    @Autowired
    ProbeClient client;

    @BeforeAll
    static void rules() {
        for (RuleType type : RuleType.SERVICE_TYPES) {
            RULES.put(type.dataId(APPLICATION), "[]");
        }
    }

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
        LAST_FAILURE.set(null);
    }

    @Test
    void countsServerErrorsAndTransportFailuresButNotClientErrors() {
        ClusterNode node = node();
        long before = node == null ? 0 : node.totalException();

        assertThat(client.ok()).contains("ok");
        assertThatThrownBy(client::notFound).isInstanceOf(MappedFailure.class);
        assertThatThrownBy(client::serverError).isInstanceOf(MappedFailure.class);
        assertThatThrownBy(client::unreachable).isInstanceOf(MappedFailure.class);

        assertThat(node().totalException() - before).isEqualTo(2);
    }

    @Test
    void blockedCallsReachTheCallerAsUnavailable() {
        RULES.publish(RuleType.FLOW.dataId(APPLICATION), "[{\"resource\":\"" + RESOURCE + "\",\"count\":0}]");

        assertThatThrownBy(client::ok).isInstanceOf(MappedFailure.class);
        DownstreamFailure failure = LAST_FAILURE.get();
        assertThat(failure.kind()).isEqualTo(DownstreamFailureKind.UNAVAILABLE);
        assertThat(failure.cause()).isInstanceOf(SentinelBlockedCallException.class);
        assertThat(((SentinelBlockedCallException) failure.cause()).resource()).isEqualTo(RESOURCE);
        assertThat(FlowRuleManager.getRules()).extracting(FlowRule::getResource).containsExactly(RESOURCE);
    }

    private static ClusterNode node() {
        return ClusterBuilderSlot.getClusterNodeMap().entrySet().stream()
                .filter(entry -> entry.getKey().getName().equals(RESOURCE))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @FeignClient(name = CLIENT)
    interface ProbeClient {

        @GetMapping("/ok")
        String ok();

        @GetMapping("/not-found")
        String notFound();

        @GetMapping("/server-error")
        String serverError();

        @GetMapping("/unreachable")
        String unreachable();
    }

    static final class MappedFailure extends RuntimeException {

        MappedFailure(DownstreamFailure failure) {
            super(failure.kind().name(), null, false, false);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = ProbeClient.class)
    static class FeignProbeApplication {

        @Bean
        RuleConfigSource ruleConfigSource() {
            return RULES;
        }

        @Bean
        Client downstream() {
            return (request, options) -> {
                String path = java.net.URI.create(request.url()).getPath();
                if (path.equals("/unreachable")) {
                    throw new java.net.ConnectException("connection refused");
                }
                int status = switch (path) {
                    case "/not-found" -> 404;
                    case "/server-error" -> 500;
                    default -> 200;
                };
                String body = status == 200
                        ? "{\"success\":true,\"result\":\"ok\"}"
                        : "{\"success\":false,\"code\":\"" + status + "\",\"message\":\"failed\"}";
                return Response.builder()
                        .request(request)
                        .status(status)
                        .reason("probe")
                        .headers(Map.of("Content-Type", List.of("application/json")))
                        .body(body, StandardCharsets.UTF_8)
                        .build();
            };
        }

        @Bean
        DownstreamFailureMapper probeDownstreamMapper() {
            return new DownstreamFailureMapper() {
                @Override
                public String clientName() {
                    return CLIENT;
                }

                @Override
                public RuntimeException map(DownstreamFailure failure) {
                    LAST_FAILURE.set(failure);
                    return new MappedFailure(failure);
                }
            };
        }
    }
}
