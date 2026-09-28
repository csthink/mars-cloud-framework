package com.mars.cloud.sentinel.feign;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureKind;
import com.mars.cloud.feign.DownstreamFailureMapper;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleType;
import feign.Client;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes=FeignResilienceTest.Application.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"spring.application.name=feign-resilience-probe","spring.cloud.gateway.server.webflux.enabled=false"})
class FeignResilienceTest {
    static final InMemoryRuleConfigSource RULES=new InMemoryRuleConfigSource();
    static final AtomicInteger circuitCalls=new AtomicInteger(),concurrentCalls=new AtomicInteger();
    static volatile CountDownLatch entered,release;
    @Autowired CircuitClient circuit;
    @Autowired ConcurrentClient concurrent;
    @BeforeAll static void rules() {for(RuleType type:RuleType.SERVICE_TYPES)RULES.put(type.dataId("feign-resilience-probe"),"[]");}
    @AfterEach void clear() {FlowRuleManager.loadRules(List.of());DegradeRuleManager.loadRules(List.of());}
    @Test void exceptionRatioOpensCircuitWithoutCallingTransportAndAllowsRecoveryProbe() {
        circuitCalls.set(0);
        RULES.publish(RuleType.DEGRADE.dataId("feign-resilience-probe"),
                "[{\"resource\":\"feign:resilience-circuit\",\"grade\":1,\"count\":0.5,\"minRequestAmount\":2,\"statIntervalMs\":1000,\"timeWindow\":1}]");
        assertThatThrownBy(circuit::failure).isInstanceOf(MappedFailure.class);
        assertThatThrownBy(circuit::failure).isInstanceOf(MappedFailure.class);
        int before=circuitCalls.get();
        assertThatThrownBy(circuit::ok).isInstanceOfSatisfying(MappedFailure.class,f->assertThat(f.kind).isEqualTo(DownstreamFailureKind.UNAVAILABLE));
        assertThat(circuitCalls.get()).isEqualTo(before);
        await().ignoreException(MappedFailure.class).atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(circuit.ok()).isEqualTo("{\"success\":true,\"result\":\"7\"}"));
        assertThat(circuitCalls.get()).isEqualTo(before+1);
    }
    @Test void concurrencyLimitRejectsBeforeTransportAndRecoversWhenTheFirstCallCompletes() throws Exception {
        concurrentCalls.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(1);
        RULES.publish(RuleType.FLOW.dataId("feign-resilience-probe"),"[{\"resource\":\"feign:resilience-concurrent\",\"grade\":0,\"count\":1}]");
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(concurrent::slow);
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(concurrent::slow).isInstanceOfSatisfying(MappedFailure.class,f->assertThat(f.kind).isEqualTo(DownstreamFailureKind.UNAVAILABLE));
                assertThat(concurrentCalls.get()).isEqualTo(1);
            } finally {release.countDown();}
            assertThat(first.get(2,TimeUnit.SECONDS)).isEqualTo("{\"success\":true,\"result\":\"7\"}");
            assertThat(concurrent.slow()).isEqualTo("{\"success\":true,\"result\":\"7\"}");assertThat(concurrentCalls.get()).isEqualTo(2);
        }
    }
    @Test void syntheticEntitlementAndPriceCallersDoNotConvertFailureToSuccess() {
        assertThatThrownBy(()->Boolean.parseBoolean(circuit.failure())).isInstanceOf(MappedFailure.class);
        assertThatThrownBy(()->new java.math.BigDecimal(circuit.failure())).isInstanceOf(MappedFailure.class);
    }
    @FeignClient(name="resilience-circuit") interface CircuitClient {
        @GetMapping("/failure") String failure();
        @GetMapping("/ok") String ok();
    }
    @FeignClient(name="resilience-concurrent") interface ConcurrentClient { @GetMapping("/slow") String slow(); }
    static class MappedFailure extends RuntimeException {
        final DownstreamFailureKind kind;
        MappedFailure(DownstreamFailure failure) {super(failure.kind().name(),null,false,false);kind=failure.kind();}
    }
    static DownstreamFailureMapper mapper(String name) {
        return new DownstreamFailureMapper() {
            public String clientName() {return name;}
            public RuntimeException map(DownstreamFailure failure) {return new MappedFailure(failure);}
        };
    }
    @SpringBootConfiguration @EnableAutoConfiguration @EnableFeignClients(clients={CircuitClient.class,ConcurrentClient.class})
    static class Application {
        @Bean RuleConfigSource rules() {return RULES;}
        @Bean DownstreamFailureMapper circuitMapper() {return mapper("resilience-circuit");}
        @Bean DownstreamFailureMapper concurrencyMapper() {return mapper("resilience-concurrent");}
        @Bean Client transport() {return (request,options)->{
            String path=java.net.URI.create(request.url()).getPath();
            if(path.equals("/slow")) {
                concurrentCalls.incrementAndGet();entered.countDown();
                try {if(!release.await(3,TimeUnit.SECONDS))throw new java.io.IOException("Test request release timed out");}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new java.io.IOException("Test interrupted");}
            } else circuitCalls.incrementAndGet();
            int status=path.equals("/failure")?500:200;
            return Response.builder().request(request).status(status).reason("test").headers(Map.of("Content-Type",List.of("application/json")))
                    .body(status==200?"{\"success\":true,\"result\":\"7\"}":"{\"success\":false,\"code\":\"500\",\"message\":\"failed\"}",StandardCharsets.UTF_8).build();
        };}
    }
}
