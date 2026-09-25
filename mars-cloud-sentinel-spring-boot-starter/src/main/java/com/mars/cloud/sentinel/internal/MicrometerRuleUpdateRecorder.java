package com.mars.cloud.sentinel.internal;

import com.mars.cloud.sentinel.rule.RuleType;
import com.mars.cloud.sentinel.rule.RuleUpdateRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * 规则来源的三个指标。告警按 {@value #SOURCE_VALID} 为 0 触发：它表示最近一次更新被拒绝、上一批规则仍在生效。
 *
 * @since 2026-09-25
 */
public final class MicrometerRuleUpdateRecorder implements RuleUpdateRecorder {

    public static final String UPDATES = "mars.sentinel.rule.updates";
    public static final String SOURCE_VALID = "mars.sentinel.rule.source.valid";
    public static final String ACTIVE_RULES = "mars.sentinel.rules.active";

    private final MeterRegistry registry;

    public MicrometerRuleUpdateRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void watch(RuleType type, IntSupplier activeRules, BooleanSupplier lastUpdateAccepted) {
        Gauge.builder(SOURCE_VALID, lastUpdateAccepted, supplier -> supplier.getAsBoolean() ? 1 : 0)
                .description("最近一次规则更新是否被接受：1 为接受，0 为拒绝且上一批规则仍在生效")
                .tag("rule_type", type.id())
                .strongReference(true)
                .register(registry);
        Gauge.builder(ACTIVE_RULES, activeRules, IntSupplier::getAsInt)
                .description("当前生效的规则条数")
                .tag("rule_type", type.id())
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void accepted(RuleType type) {
        counter(type, "accepted").increment();
    }

    @Override
    public void rejected(RuleType type) {
        counter(type, "rejected").increment();
    }

    private Counter counter(RuleType type, String outcome) {
        return Counter.builder(UPDATES)
                .description("运行期规则更新的结果")
                .tag("rule_type", type.id())
                .tag("outcome", outcome)
                .register(registry);
    }
}
