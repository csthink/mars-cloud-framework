package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务部署物订阅四个 Data ID；缺任何一个都启动失败，已登记的监听随之解除；
 * 同一个目录下不同规则类型的更新在同一把锁里串行地校验与装入。
 */
class SentinelRuleSourcesTest {

    private final InMemoryRuleConfigSource config = new InMemoryRuleConfigSource();

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
        ParamFlowRuleManager.loadRules(List.of());
        SystemRuleManager.loadRules(List.of());
    }

    @Test
    void loadsAllFourServiceDataIds() {
        for (RuleType type : RuleType.SERVICE_TYPES) {
            config.put(type.dataId("sources-probe"), "[]");
        }
        SentinelRuleSources sources = sources("sources-probe");
        sources.afterSingletonsInstantiated();

        assertThat(sources.sources()).extracting(NacosRuleSource::dataId).containsExactly(
                "sources-probe-sentinel-flow-rules.json",
                "sources-probe-sentinel-degrade-rules.json",
                "sources-probe-sentinel-param-flow-rules.json",
                "sources-probe-sentinel-system-rules.json");
        sources.destroy();
        assertThat(config.listenerCount("sources-probe-sentinel-flow-rules.json")).isZero();
    }

    @Test
    void failsWhenAnyDataIdIsMissingAndReleasesTheListenersAlreadyRegistered() {
        config.put(RuleType.FLOW.dataId("sources-missing"), "[]");
        config.put(RuleType.DEGRADE.dataId("sources-missing"), "[]");

        SentinelRuleSources sources = sources("sources-missing");
        assertThatThrownBy(sources::afterSingletonsInstantiated)
                .hasMessageContaining("sources-missing-sentinel-param-flow-rules.json")
                .hasMessageContaining("配置不存在");
        assertThat(config.listenerCount(RuleType.FLOW.dataId("sources-missing"))).isZero();
    }

    @Test
    void requiresAnApplicationName() {
        assertThatThrownBy(() -> sources(" "))
                .hasMessageContaining("spring.application.name");
    }

    /**
     * 网关的两种规则在校验时读另一种规则的现状。一种规则还在校验时，另一种规则的更新必须等它装入后才开始，
     * 否则「删除分组」与「新增对它的引用」可以同时通过。
     */
    @Test
    void updatesOfDifferentRuleTypesAreSerializedUnderOneLock() throws Exception {
        CountDownLatch slowParsing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        RuleKind<List<String>> slow = kind(RuleType.FLOW, content -> {
            if (content.equals("slow")) {
                order.add("slow-start");
                slowParsing.countDown();
                await(release);
                order.add("slow-end");
            }
            return List.of();
        });
        RuleKind<List<String>> fast = kind(RuleType.DEGRADE, content -> {
            if (content.equals("fast")) {
                order.add("fast");
            }
            return List.of();
        });
        String slowId = RuleType.FLOW.dataId("lock-probe");
        String fastId = RuleType.DEGRADE.dataId("lock-probe");
        config.put(slowId, "[]").put(fastId, "[]");
        SentinelRuleSources sources = new SentinelRuleSources("lock-probe", new SentinelRuleCatalog(List.of(slow, fast)),
                config, Duration.ofSeconds(1), RuleUpdateRecorder.NONE);
        sources.afterSingletonsInstantiated();
        try {
            Thread first = new Thread(() -> config.publish(slowId, "slow"), "slow-update");
            first.start();
            assertThat(slowParsing.await(5, TimeUnit.SECONDS)).isTrue();
            Thread second = new Thread(() -> config.publish(fastId, "fast"), "fast-update");
            second.start();

            second.join(300);
            assertThat(second.isAlive()).as("另一种规则的更新在锁外等待").isTrue();
            assertThat(order).containsExactly("slow-start");

            release.countDown();
            first.join(5_000);
            second.join(5_000);
            assertThat(order).containsExactly("slow-start", "slow-end", "fast");
        } finally {
            release.countDown();
            sources.destroy();
        }
    }

    private static RuleKind<List<String>> kind(RuleType type, Function<String, List<String>> parser) {
        return new RuleKind<>() {
            @Override
            public RuleType type() {
                return type;
            }

            @Override
            public List<String> parse(String content) {
                return parser.apply(content);
            }

            @Override
            public void register(SentinelProperty<List<String>> property) {
            }

            @Override
            public int size(List<String> rules) {
                return rules.size();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private SentinelRuleSources sources(String applicationName) {
        return new SentinelRuleSources(applicationName, SentinelRuleCatalog.services(), config,
                Duration.ofSeconds(1), RuleUpdateRecorder.NONE);
    }
}
