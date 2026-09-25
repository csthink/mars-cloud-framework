package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务部署物订阅四个 Data ID；缺任何一个都启动失败，已登记的监听随之解除。
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

    private SentinelRuleSources sources(String applicationName) {
        return new SentinelRuleSources(applicationName, SentinelRuleCatalog.services(), config,
                Duration.ofSeconds(1), RuleUpdateRecorder.NONE);
    }
}
