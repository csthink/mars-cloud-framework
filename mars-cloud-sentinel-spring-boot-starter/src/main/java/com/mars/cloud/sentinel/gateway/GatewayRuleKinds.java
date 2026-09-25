package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.mars.cloud.sentinel.rule.RuleFields;
import com.mars.cloud.sentinel.rule.RuleKind;
import com.mars.cloud.sentinel.rule.RuleRejectedException;
import com.mars.cloud.sentinel.rule.RuleType;
import com.mars.cloud.sentinel.rule.StrictJson;
import tools.jackson.core.type.TypeReference;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 网关部署物的两种规则：{@code gw-api-group}（API 分组）与 {@code gw-flow}（网关限流）。
 *
 * <p>除字段表与 Sentinel 自身的合法性判断外，还核对名字引用：路由模式的限流规则必须指向已声明的路由 ID，
 * 分组模式的必须指向当前已生效的分组；删掉仍被限流规则引用的分组时，分组的这次更新整批拒绝。
 * 写错名字的规则在 Sentinel 里永远不会命中，这里把它变成拒绝。
 *
 * @since 2026-09-25
 */
public final class GatewayRuleKinds {

    record PathItemDocument(String pattern, Integer matchStrategy) {
    }

    record ApiGroupDocument(String apiName, List<PathItemDocument> predicateItems) {
    }

    record ParamItemDocument(Integer parseStrategy, String fieldName, String pattern, Integer matchStrategy) {
    }

    record GatewayFlowRuleDocument(String resource, Integer resourceMode, Integer grade, Double count,
                                   Long intervalSec, Integer controlBehavior, Integer burst,
                                   Integer maxQueueingTimeoutMs, ParamItemDocument paramItem) {
    }

    private final Supplier<Set<String>> routeIds;

    /**
     * @param routeIds 网关当前声明的路由 ID；每次校验时读取，路由刷新后按新值核对
     */
    public GatewayRuleKinds(Supplier<Set<String>> routeIds) {
        this.routeIds = routeIds;
    }

    public List<RuleKind<?>> all() {
        return List.of(apiGroups(), flowRules());
    }

    RuleKind<Set<ApiDefinition>> apiGroups() {
        return new SetKind<>(RuleType.GATEWAY_API_GROUP) {
            @Override
            public Set<ApiDefinition> parse(String content) {
                List<ApiGroupDocument> documents = StrictJson.readList(content, new TypeReference<>() {
                });
                Set<ApiDefinition> groups = new LinkedHashSet<>();
                Set<String> names = new HashSet<>();
                for (int i = 0; i < documents.size(); i++) {
                    ApiGroupDocument d = documents.get(i);
                    String name = RuleFields.requireText(i, "apiName", d.apiName());
                    if (!names.add(name)) {
                        throw RuleFields.rejected(i, "apiName", "与前面的分组重名：" + name);
                    }
                    if (d.predicateItems() == null || d.predicateItems().isEmpty()) {
                        throw RuleFields.rejected(i, "predicateItems", "至少要有一个路径匹配项");
                    }
                    Set<ApiPredicateItem> items = new LinkedHashSet<>();
                    for (PathItemDocument item : d.predicateItems()) {
                        items.add(pathItem(i, item));
                    }
                    ApiDefinition group = new ApiDefinition(name).setPredicateItems(items);
                    if (!GatewayApiDefinitionManager.isValidApi(group)) {
                        throw RuleFields.rejected(i, "不是合法的 API 分组（Sentinel 的合法性判断未通过）");
                    }
                    groups.add(group);
                }
                Set<String> referenced = GatewayRuleManager.getRules().stream()
                        .filter(rule -> rule.getResourceMode() == SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                        .map(GatewayFlowRule::getResource)
                        .filter(resource -> !names.contains(resource))
                        .collect(Collectors.toCollection(TreeSet::new));
                if (!referenced.isEmpty()) {
                    throw new RuleRejectedException("删掉了仍被网关限流规则引用的分组：" + referenced
                            + "；先从 gw-flow 规则里去掉对它们的引用");
                }
                return groups;
            }

            @Override
            public void register(SentinelProperty<Set<ApiDefinition>> property) {
                GatewayApiDefinitionManager.register2Property(property);
            }
        };
    }

    RuleKind<Set<GatewayFlowRule>> flowRules() {
        return new SetKind<>(RuleType.GATEWAY_FLOW) {
            @Override
            public Set<GatewayFlowRule> parse(String content) {
                List<GatewayFlowRuleDocument> documents = StrictJson.readList(content, new TypeReference<>() {
                });
                Set<String> routes = routeIds.get();
                Set<String> groups = GatewayApiDefinitionManager.getApiDefinitions().stream()
                        .map(ApiDefinition::getApiName)
                        .collect(Collectors.toSet());
                Set<GatewayFlowRule> rules = new LinkedHashSet<>();
                for (int i = 0; i < documents.size(); i++) {
                    GatewayFlowRuleDocument d = documents.get(i);
                    GatewayFlowRule rule = new GatewayFlowRule(RuleFields.requireText(i, "resource", d.resource()));
                    rule.setCount(RuleFields.require(i, "count", d.count()));
                    if (d.resourceMode() != null) {
                        rule.setResourceMode(d.resourceMode());
                    }
                    if (d.grade() != null) {
                        rule.setGrade(d.grade());
                    }
                    if (d.intervalSec() != null) {
                        rule.setIntervalSec(d.intervalSec());
                    }
                    if (d.controlBehavior() != null) {
                        rule.setControlBehavior(d.controlBehavior());
                    }
                    if (d.burst() != null) {
                        rule.setBurst(d.burst());
                    }
                    if (d.maxQueueingTimeoutMs() != null) {
                        rule.setMaxQueueingTimeoutMs(d.maxQueueingTimeoutMs());
                    }
                    if (d.paramItem() != null) {
                        rule.setParamItem(paramItem(i, d.paramItem()));
                    }
                    if (!GatewayRuleManager.isValidRule(rule)) {
                        throw RuleFields.rejected(i, "不是合法的网关限流规则（Sentinel 的合法性判断未通过：检查 resourceMode、grade、count、intervalSec、controlBehavior 与 paramItem）");
                    }
                    switch (rule.getResourceMode()) {
                        case SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID -> {
                            if (!routes.contains(rule.getResource())) {
                                throw RuleFields.rejected(i, "resource", "不是网关已声明的路由 ID：" + rule.getResource() + "（已声明 " + new TreeSet<>(routes) + "）");
                            }
                        }
                        case SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME -> {
                            if (!groups.contains(rule.getResource())) {
                                throw RuleFields.rejected(i, "resource", "不是当前已生效的 API 分组：" + rule.getResource() + "（先在 gw-api-group 里定义它）");
                            }
                        }
                        default -> throw RuleFields.rejected(i, "resourceMode", "只能是 0（路由 ID）或 1（API 分组）");
                    }
                    rules.add(rule);
                }
                return rules;
            }

            @Override
            public void register(SentinelProperty<Set<GatewayFlowRule>> property) {
                GatewayRuleManager.register2Property(property);
            }
        };
    }

    private static ApiPathPredicateItem pathItem(int index, PathItemDocument item) {
        if (item == null) {
            throw RuleFields.rejected(index, "predicateItems", "不能含 null");
        }
        String pattern = RuleFields.requireText(index, "predicateItems.pattern", item.pattern());
        int strategy = item.matchStrategy() == null
                ? SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT : item.matchStrategy();
        switch (strategy) {
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT -> {
                if (!pattern.startsWith("/")) {
                    throw RuleFields.rejected(index, "predicateItems.pattern", "按精确匹配时必须以 / 开头：" + pattern);
                }
            }
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX -> {
                // Spring Cloud Gateway 的适配按 Ant 路径匹配前缀项，不以 /** 结尾的前缀只会匹配它自己
                if (!pattern.startsWith("/") || !pattern.endsWith("/**")) {
                    throw RuleFields.rejected(index, "predicateItems.pattern", "按前缀匹配时必须以 / 开头、以 /** 结尾：" + pattern);
                }
            }
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_REGEX -> {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    throw RuleFields.rejected(index, "predicateItems.pattern", "不是合法的正则表达式：" + ex.getDescription());
                }
            }
            default -> throw RuleFields.rejected(index, "predicateItems.matchStrategy", "只能是 0（精确）、1（前缀）或 2（正则）");
        }
        return new ApiPathPredicateItem().setPattern(pattern).setMatchStrategy(strategy);
    }

    private static GatewayParamFlowItem paramItem(int index, ParamItemDocument item) {
        GatewayParamFlowItem paramItem = new GatewayParamFlowItem()
                .setParseStrategy(RuleFields.require(index, "paramItem.parseStrategy", item.parseStrategy()));
        if (item.fieldName() != null) {
            paramItem.setFieldName(item.fieldName());
        }
        if (item.pattern() != null) {
            paramItem.setPattern(item.pattern());
        }
        if (item.matchStrategy() != null) {
            paramItem.setMatchStrategy(item.matchStrategy());
        }
        return paramItem;
    }

    private abstract static class SetKind<R> implements RuleKind<Set<R>> {

        private final RuleType type;

        SetKind(RuleType type) {
            this.type = type;
        }

        @Override
        public RuleType type() {
            return type;
        }

        @Override
        public int size(Set<R> rules) {
            return rules.size();
        }
    }
}
