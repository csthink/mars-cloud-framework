package com.mars.cloud.sentinel.rule;

import java.util.List;

/**
 * 部署物订阅的规则种类，按装入顺序排列。网关装配提供网关的两种，其余部署物是服务的四种。
 *
 * @since 2026-09-25
 */
public record SentinelRuleCatalog(List<RuleKind<?>> kinds) {

    public SentinelRuleCatalog {
        kinds = List.copyOf(kinds);
    }

    public static SentinelRuleCatalog services() {
        return new SentinelRuleCatalog(ServiceRuleKinds.all());
    }
}
