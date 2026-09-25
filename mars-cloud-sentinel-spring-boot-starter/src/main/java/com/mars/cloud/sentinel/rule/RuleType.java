package com.mars.cloud.sentinel.rule;

import java.util.List;

/**
 * 规则类型与它在 Nacos 里的 Data ID。
 *
 * <p>每种规则一个 Data ID：{@code <应用名>-sentinel-<类型>-rules.json}，Group 固定为 {@value #GROUP}，
 * 与应用配置同一个命名空间。部署物需要哪些类型由它是不是网关决定，不提供配置项。
 *
 * @since 2026-09-25
 */
public enum RuleType {

    FLOW("flow"),
    DEGRADE("degrade"),
    PARAM_FLOW("param-flow"),
    SYSTEM("system"),
    GATEWAY_API_GROUP("gw-api-group"),
    GATEWAY_FLOW("gw-flow");

    /** 规则 Data ID 所在的 Group。 */
    public static final String GROUP = "SENTINEL_GROUP";

    /** 服务部署物订阅的规则类型。 */
    public static final List<RuleType> SERVICE_TYPES = List.of(FLOW, DEGRADE, PARAM_FLOW, SYSTEM);

    /** 网关部署物订阅的规则类型；分组先于网关限流规则装入，后者按名字引用前者。 */
    public static final List<RuleType> GATEWAY_TYPES = List.of(GATEWAY_API_GROUP, GATEWAY_FLOW);

    private final String id;

    RuleType(String id) {
        this.id = id;
    }

    /** Data ID 里的类型段，也是指标标签 {@code rule_type} 的取值。 */
    public String id() {
        return id;
    }

    public String dataId(String applicationName) {
        return applicationName + "-sentinel-" + id + "-rules.json";
    }
}
