package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 服务规则的字段表与校验：合法的整批通过，任一条不合法整批拒绝，拒绝信息定位到第几条、哪个字段。
 */
class ServiceRuleKindsTest {

    @Test
    void acceptsFlowRulesAndKeepsSentinelDefaultsForOmittedFields() {
        List<FlowRule> rules = ServiceRuleKinds.FLOW.parse("""
                [{"resource":"GET:/product/v1/items","count":20},
                 {"resource":"POST:/order/v1/callbacks/{channel}","grade":1,"count":5,"controlBehavior":2,"maxQueueingTimeMs":500,"limitApp":"default","clusterMode":false,"regex":false}]
                """);

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).getGrade()).isEqualTo(RuleConstant.FLOW_GRADE_QPS);
        assertThat(rules.get(0).getLimitApp()).isEqualTo(RuleConstant.LIMIT_APP_DEFAULT);
        assertThat(rules.get(1).getControlBehavior()).isEqualTo(RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER);
        assertThat(rules.get(1).getMaxQueueingTimeMs()).isEqualTo(500);
    }

    @Test
    void acceptsAnEmptyBatch() {
        assertThat(ServiceRuleKinds.FLOW.parse("[]")).isEmpty();
        assertThat(ServiceRuleKinds.SYSTEM.parse(" [ ] ")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            顶层不是数组        | {"resource":"a","count":1}                               | 格式
            字段名拼错          | [{"resource":"a","cout":1}]                              | cout
            字段重复            | [{"resource":"a","count":1,"count":2}]                   | 格式
            字符串冒充数字      | [{"resource":"a","count":"1"}]                           | count
            小数冒充整数        | [{"resource":"a","count":1,"grade":1.5}]                 | grade
            数组里有 null       | [null]                                                   | 第 1 条规则为 null
            缺少 count          | [{"resource":"a"}]                                       | 第 1 条规则的 count 必须给出
            资源名空白          | [{"resource":" ","count":1}]                             | 第 1 条规则的 resource 不能为空
            其他调用来源        | [{"resource":"a","count":1,"limitApp":"console"}]       | limitApp 只能是 default
            集群模式            | [{"resource":"a","count":1,"clusterMode":true}]          | clusterMode 只能是 false
            正则资源名          | [{"resource":"a","count":1,"regex":true}]                | regex 只能是 false
            Sentinel 判为不合法 | [{"resource":"a","count":-1}]                            | 不是合法的流量规则
            第二条不合法        | [{"resource":"a","count":1},{"resource":"b","count":1,"grade":9}] | 第 2 条规则
            结尾有多余内容      | [] []                                                    | 格式
            """)
    void rejectsTheWholeFlowBatch(String scenario, String content, String reason) {
        assertThatThrownBy(() -> ServiceRuleKinds.FLOW.parse(content))
                .as(scenario)
                .isInstanceOf(RuleRejectedException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void validatesDegradeRules() {
        List<DegradeRule> rules = ServiceRuleKinds.DEGRADE.parse("""
                [{"resource":"feign:mars-cloud-upms-service","grade":1,"count":0.5,"timeWindow":10,"minRequestAmount":5,"statIntervalMs":1000}]
                """);
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule.getGrade()).isEqualTo(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
            assertThat(rule.getTimeWindow()).isEqualTo(10);
        });

        assertThatThrownBy(() -> ServiceRuleKinds.DEGRADE.parse("[{\"resource\":\"a\",\"count\":1}]"))
                .hasMessageContaining("timeWindow 必须给出");
        assertThatThrownBy(() -> ServiceRuleKinds.DEGRADE.parse("[{\"resource\":\"a\",\"grade\":1,\"count\":2,\"timeWindow\":10}]"))
                .hasMessageContaining("不是合法的熔断降级规则");
    }

    @Test
    void validatesParamFlowRules() {
        List<ParamFlowRule> rules = ServiceRuleKinds.PARAM_FLOW.parse("""
                [{"resource":"GET:/product/v1/entitlements","paramIdx":0,"count":10,"durationInSec":1,
                  "paramFlowItemList":[{"object":"vip","count":100,"classType":"java.lang.String"}]}]
                """);
        assertThat(rules).singleElement().satisfies(rule -> assertThat(rule.getParamFlowItemList()).hasSize(1));

        assertThatThrownBy(() -> ServiceRuleKinds.PARAM_FLOW.parse("[{\"resource\":\"a\",\"count\":1}]"))
                .hasMessageContaining("paramIdx 必须给出");
        assertThatThrownBy(() -> ServiceRuleKinds.PARAM_FLOW.parse(
                "[{\"resource\":\"a\",\"paramIdx\":0,\"count\":1,\"paramFlowItemList\":[{\"object\":\"x\"}]}]"))
                .hasMessageContaining("paramFlowItemList");
    }

    @Test
    void validatesSystemRules() {
        List<SystemRule> rules = ServiceRuleKinds.SYSTEM.parse("[{\"highestCpuUsage\":0.8},{\"qps\":500,\"avgRt\":-1}]");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).getHighestCpuUsage()).isEqualTo(0.8);
        assertThat(rules.get(1).getQps()).isEqualTo(500);

        assertThatThrownBy(() -> ServiceRuleKinds.SYSTEM.parse("[{\"qps\":-1}]"))
                .hasMessageContaining("没有启用任何阈值");
        assertThatThrownBy(() -> ServiceRuleKinds.SYSTEM.parse("[{\"highestCpuUsage\":80}]"))
                .hasMessageContaining("highestCpuUsage");
        assertThatThrownBy(() -> ServiceRuleKinds.SYSTEM.parse("[{\"maxThread\":-5}]"))
                .hasMessageContaining("maxThread 只能是 -1");
        assertThatThrownBy(() -> ServiceRuleKinds.SYSTEM.parse("[{\"resource\":\"a\",\"qps\":1}]"))
                .hasMessageContaining("resource");
    }
}
