package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.internal.MicrometerRuleUpdateRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则来源的失败语义：启动期任何问题都让启动失败；运行期坏规则、删除与空白都保留上一批并留下可告警的指标。
 */
class NacosRuleSourceTest {

    private static final String DATA_ID = RuleType.FLOW.dataId("rule-source-probe");

    private final InMemoryRuleConfigSource config = new InMemoryRuleConfigSource();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private NacosRuleSource<List<FlowRule>> source;

    @AfterEach
    void closeSource() {
        if (source != null) {
            source.close();
        }
        FlowRuleManager.loadRules(List.of());
    }

    private NacosRuleSource<List<FlowRule>> newSource() {
        source = new NacosRuleSource<>(ServiceRuleKinds.FLOW, DATA_ID, config, Duration.ofSeconds(1),
                new MicrometerRuleUpdateRecorder(registry));
        return source;
    }

    @Test
    void startupFailsWhenTheDataIdDoesNotExist() {
        assertThatThrownBy(() -> newSource().start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataId=" + DATA_ID)
                .hasMessageContaining("group=SENTINEL_GROUP")
                .hasMessageContaining("配置不存在");
    }

    @Test
    void startupFailsWhenTheContentIsBlank() {
        config.put(DATA_ID, "  \n");
        assertThatThrownBy(() -> newSource().start()).hasMessageContaining("内容为空白");
    }

    @Test
    void startupFailsWhenReadingFails() {
        config.failReads(new IllegalStateException("连接超时"));
        assertThatThrownBy(() -> newSource().start())
                .hasMessageContaining("读取失败")
                .hasRootCauseMessage("连接超时");
    }

    @Test
    void startupFailsWhenTheRulesAreInvalid() {
        config.put(DATA_ID, "[{\"resource\":\"a\",\"cout\":1}]");
        assertThatThrownBy(() -> newSource().start())
                .hasMessageContaining("规则没有通过校验")
                .hasMessageContaining("cout");
        assertThat(config.listenerCount(DATA_ID)).isZero();
    }

    @Test
    void installsValidRulesAtStartupAndListensForChanges() {
        config.put(DATA_ID, "[{\"resource\":\"probe-a\",\"count\":5}]");
        newSource().start();

        assertThat(FlowRuleManager.getRules()).extracting(FlowRule::getResource).containsExactly("probe-a");
        assertThat(config.listenerCount(DATA_ID)).isEqualTo(1);
        assertThat(valid()).isEqualTo(1);
        assertThat(active()).isEqualTo(1);

        config.publish(DATA_ID, "[{\"resource\":\"probe-a\",\"count\":5},{\"resource\":\"probe-b\",\"count\":1}]");
        assertThat(FlowRuleManager.getRules()).extracting(FlowRule::getResource).containsExactlyInAnyOrder("probe-a", "probe-b");
        assertThat(updates("accepted")).isEqualTo(1);
        assertThat(active()).isEqualTo(2);
    }

    @Test
    void keepsThePreviousRulesWhenAnUpdateIsInvalidDeletedOrBlank() {
        config.put(DATA_ID, "[{\"resource\":\"probe-keep\",\"count\":5}]");
        newSource().start();

        config.publish(DATA_ID, "[{\"resource\":\"probe-keep\",\"count\":-1}]");
        assertThat(FlowRuleManager.getRules()).extracting(FlowRule::getCount).containsExactly(5.0);
        assertThat(valid()).isZero();

        config.delete(DATA_ID);
        config.publish(DATA_ID, " ");
        assertThat(FlowRuleManager.getRules()).extracting(FlowRule::getResource).containsExactly("probe-keep");
        assertThat(updates("rejected")).isEqualTo(3);

        // 恢复为当前生效的内容：状态回到正常，不算一次新的更新
        config.publish(DATA_ID, "[{\"resource\":\"probe-keep\",\"count\":5}]");
        assertThat(valid()).isEqualTo(1);
        assertThat(updates("accepted")).isZero();
    }

    @Test
    void anEmptyArrayClearsTheRules() {
        config.put(DATA_ID, "[{\"resource\":\"probe-clear\",\"count\":5}]");
        newSource().start();

        config.publish(DATA_ID, "[]");
        assertThat(FlowRuleManager.getRules()).isEmpty();
        assertThat(valid()).isEqualTo(1);
        assertThat(active()).isZero();
    }

    @Test
    void closeRemovesTheListener() {
        config.put(DATA_ID, "[]");
        newSource().start();
        source.close();
        assertThat(config.listenerCount(DATA_ID)).isZero();
    }

    private double valid() {
        return registry.get(MicrometerRuleUpdateRecorder.SOURCE_VALID).tag("rule_type", "flow").gauge().value();
    }

    private double active() {
        return registry.get(MicrometerRuleUpdateRecorder.ACTIVE_RULES).tag("rule_type", "flow").gauge().value();
    }

    private double updates(String outcome) {
        var counter = registry.find(MicrometerRuleUpdateRecorder.UPDATES)
                .tags("rule_type", "flow", "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
