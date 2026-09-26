package com.mars.cloud.sentinel.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.mars.cloud.sentinel.rule.RuleRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关规则：字段表、Sentinel 的合法性判断，以及对路由 ID 与分组名的引用核对。
 */
class GatewayRuleKindsTest {

    private final GatewayRuleKinds kinds = new GatewayRuleKinds(() -> Set.of("order", "auth-issuer"));

    @BeforeEach
    void installGroup() {
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of(new ApiDefinition("order-callbacks")
                .setPredicateItems(Set.of(new ApiPathPredicateItem().setPattern("/order/v1/callbacks/**")
                        .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX)))));
        GatewayRuleManager.loadRules(Set.of());
    }

    @AfterEach
    void clear() {
        GatewayRuleManager.loadRules(Set.of());
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of());
    }

    @Test
    void acceptsRouteAndGroupRules() {
        Set<GatewayFlowRule> rules = kinds.flowRules().parse("""
                [{"resource":"order","count":20,"intervalSec":1,"paramItem":{"parseStrategy":0}},
                 {"resource":"order-callbacks","resourceMode":1,"count":100},
                 {"resource":"auth-issuer","count":5,"paramItem":{"parseStrategy":2,"fieldName":"X-Channel","pattern":"^app-.*$","matchStrategy":2}},
                 {"resource":"auth-issuer","count":6,"paramItem":{"parseStrategy":3,"fieldName":"client","pattern":"console","matchStrategy":3}},
                 {"resource":"auth-issuer","count":7,"paramItem":{"parseStrategy":4,"fieldName":"session","pattern":"probe"}}]
                """);
        assertThat(rules).extracting(GatewayFlowRule::getResource)
                .containsExactlyInAnyOrder("order", "order-callbacks", "auth-issuer", "auth-issuer", "auth-issuer");
        assertThat(rules).filteredOn(rule -> rule.getCount() == 7)
                .singleElement().satisfies(rule -> assertThat(rule.getParamItem().getMatchStrategy())
                        .isEqualTo(SentinelGatewayConstants.PARAM_MATCH_STRATEGY_EXACT));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            未声明的路由        | [{"resource":"orders","count":1}]                                  | 不是网关已声明的路由 ID：orders
            未定义的分组        | [{"resource":"login-entry","resourceMode":1,"count":1}]            | 不是当前已生效的 API 分组：login-entry
            资源模式越界        | [{"resource":"order","resourceMode":2,"count":1}]                  | 第 1 条规则
            缺少 count          | [{"resource":"order"}]                                             | count 必须给出
            参数项缺解析方式    | [{"resource":"order","count":1,"paramItem":{"fieldName":"x"}}]    | paramItem.parseStrategy 必须给出
            请求头参数缺字段名  | [{"resource":"order","count":1,"paramItem":{"parseStrategy":2}}]  | 不是合法的网关限流规则
            参数项的内部字段    | [{"resource":"order","count":1,"paramItem":{"parseStrategy":0,"index":0}}] | index
            解析方式越界        | [{"resource":"order","count":1,"paramItem":{"parseStrategy":5}}]  | paramItem.parseStrategy 只能是 0（客户端地址）
            解析方式为负        | [{"resource":"order","count":1,"paramItem":{"parseStrategy":-1}}] | paramItem.parseStrategy 只能是 0（客户端地址）
            前缀匹配没有实现    | [{"resource":"order","count":1,"paramItem":{"parseStrategy":2,"fieldName":"h","pattern":"a","matchStrategy":1}}] | paramItem.matchStrategy 只能是 0（精确）、2（正则）或 3（包含）
            匹配方式越界        | [{"resource":"order","count":1,"paramItem":{"parseStrategy":2,"fieldName":"h","pattern":"a","matchStrategy":4}}] | paramItem.matchStrategy 只能是 0（精确）
            参数正则写错        | [{"resource":"order","count":1,"paramItem":{"parseStrategy":2,"fieldName":"h","pattern":"(","matchStrategy":2}}] | paramItem.pattern 不是合法的正则表达式
            没有 pattern 的匹配方式 | [{"resource":"order","count":1,"paramItem":{"parseStrategy":0,"matchStrategy":0}}] | paramItem.matchStrategy 只在给出 pattern 时有意义
            空白的 pattern      | [{"resource":"order","count":1,"paramItem":{"parseStrategy":2,"fieldName":"h","pattern":" "}}] | paramItem.pattern 不能为空白
            """)
    void rejectsInvalidGatewayFlowRules(String scenario, String content, String reason) {
        assertThatThrownBy(() -> kinds.flowRules().parse(content))
                .as(scenario)
                .isInstanceOf(RuleRejectedException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void acceptsApiGroups() {
        Set<ApiDefinition> groups = kinds.apiGroups().parse("""
                [{"apiName":"order-callbacks","predicateItems":[{"pattern":"/order/v1/callbacks/**","matchStrategy":1}]},
                 {"apiName":"login-entry","predicateItems":[{"pattern":"/oauth2/token"},{"pattern":"^/login/sms/.*$","matchStrategy":2}]}]
                """);
        assertThat(groups).extracting(ApiDefinition::getApiName).containsExactlyInAnyOrder("order-callbacks", "login-entry");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            前缀不以 /** 结尾 | [{"apiName":"g","predicateItems":[{"pattern":"/order/v1/callbacks/","matchStrategy":1}]}] | 以 /** 结尾
            精确路径不以 / 开头 | [{"apiName":"g","predicateItems":[{"pattern":"order"}]}]                          | 必须以 / 开头
            正则写错           | [{"apiName":"g","predicateItems":[{"pattern":"(","matchStrategy":2}]}]          | 不是合法的正则表达式
            匹配方式越界       | [{"apiName":"g","predicateItems":[{"pattern":"/a","matchStrategy":3}]}]         | matchStrategy
            没有匹配项         | [{"apiName":"g","predicateItems":[]}]                                           | 至少要有一个路径匹配项
            分组重名           | [{"apiName":"g","predicateItems":[{"pattern":"/a"}]},{"apiName":"g","predicateItems":[{"pattern":"/b"}]}] | 重名
            """)
    void rejectsInvalidApiGroups(String scenario, String content, String reason) {
        assertThatThrownBy(() -> kinds.apiGroups().parse(content))
                .as(scenario)
                .isInstanceOf(RuleRejectedException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void refusesToRemoveAGroupThatAFlowRuleStillReferences() {
        GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("order-callbacks")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME).setCount(1)));

        assertThatThrownBy(() -> kinds.apiGroups().parse("""
                [{"apiName":"other","predicateItems":[{"pattern":"/a"}]}]
                """))
                .hasMessageContaining("删掉了仍被网关限流规则引用的分组：[order-callbacks]");
    }
}
