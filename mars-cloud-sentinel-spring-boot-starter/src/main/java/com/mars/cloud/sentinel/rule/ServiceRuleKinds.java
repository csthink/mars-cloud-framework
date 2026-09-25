package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleUtil;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 服务部署物的四种规则：{@code flow}、{@code degrade}、{@code param-flow}、{@code system}。
 *
 * <p>每种规则只接受下列记录里的字段。{@code limitApp} 只能是 {@code default}（本项目不配置调用来源解析，
 * 其他取值永远不会命中）；{@code clusterMode} 只能是 {@code false}（不使用集群限流）；{@code regex} 只能是
 * {@code false}（资源名按字面匹配）。缺省的字段取 Sentinel 的默认值。
 *
 * @since 2026-09-25
 */
public final class ServiceRuleKinds {

    private ServiceRuleKinds() {
    }

    public static List<RuleKind<?>> all() {
        return List.of(FLOW, DEGRADE, PARAM_FLOW, SYSTEM);
    }

    record FlowRuleDocument(String resource, String limitApp, Integer grade, Double count, Integer strategy,
                            String refResource, Integer controlBehavior, Integer warmUpPeriodSec,
                            Integer maxQueueingTimeMs, Boolean clusterMode, Boolean regex) {
    }

    record DegradeRuleDocument(String resource, String limitApp, Integer grade, Double count, Integer timeWindow,
                               Integer minRequestAmount, Double slowRatioThreshold, Integer statIntervalMs,
                               Boolean regex) {
    }

    record ParamFlowItemDocument(String object, Integer count, String classType) {
    }

    record ParamFlowRuleDocument(String resource, String limitApp, Integer grade, Integer paramIdx, Double count,
                                 Integer controlBehavior, Integer maxQueueingTimeMs, Integer burstCount,
                                 Long durationInSec, List<ParamFlowItemDocument> paramFlowItemList,
                                 Boolean clusterMode, Boolean regex) {
    }

    record SystemRuleDocument(Double highestSystemLoad, Double highestCpuUsage, Double qps, Long avgRt,
                              Long maxThread) {
    }

    static final RuleKind<List<FlowRule>> FLOW = new ListKind<>(RuleType.FLOW, FlowRuleManager::register2Property) {
        @Override
        public List<FlowRule> parse(String content) {
            List<FlowRuleDocument> documents = StrictJson.readList(content, new TypeReference<>() {
            });
            List<FlowRule> rules = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                FlowRuleDocument d = documents.get(i);
                FlowRule rule = new FlowRule(RuleFields.requireText(i, "resource", d.resource()));
                rule.setLimitApp(Constraints.defaultLimitApp(i, d.limitApp()));
                Constraints.requireFalse(i, "clusterMode", d.clusterMode());
                Constraints.requireFalse(i, "regex", d.regex());
                rule.setCount(RuleFields.require(i, "count", d.count()));
                RuleFields.ifPresent(d.grade(), rule::setGrade);
                RuleFields.ifPresent(d.strategy(), rule::setStrategy);
                RuleFields.ifPresent(d.refResource(), rule::setRefResource);
                RuleFields.ifPresent(d.controlBehavior(), rule::setControlBehavior);
                RuleFields.ifPresent(d.warmUpPeriodSec(), rule::setWarmUpPeriodSec);
                RuleFields.ifPresent(d.maxQueueingTimeMs(), rule::setMaxQueueingTimeMs);
                if (!FlowRuleUtil.isValidRule(rule)) {
                    throw RuleFields.rejected(i, "不是合法的流量规则（Sentinel 的合法性判断未通过：检查 grade、count、strategy、controlBehavior 与其依赖字段）");
                }
                rules.add(rule);
            }
            return rules;
        }
    };

    static final RuleKind<List<DegradeRule>> DEGRADE = new ListKind<>(RuleType.DEGRADE, DegradeRuleManager::register2Property) {
        @Override
        public List<DegradeRule> parse(String content) {
            List<DegradeRuleDocument> documents = StrictJson.readList(content, new TypeReference<>() {
            });
            List<DegradeRule> rules = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                DegradeRuleDocument d = documents.get(i);
                DegradeRule rule = new DegradeRule(RuleFields.requireText(i, "resource", d.resource()));
                rule.setLimitApp(Constraints.defaultLimitApp(i, d.limitApp()));
                Constraints.requireFalse(i, "regex", d.regex());
                rule.setCount(RuleFields.require(i, "count", d.count()));
                rule.setTimeWindow(RuleFields.require(i, "timeWindow", d.timeWindow()));
                RuleFields.ifPresent(d.grade(), rule::setGrade);
                RuleFields.ifPresent(d.minRequestAmount(), rule::setMinRequestAmount);
                RuleFields.ifPresent(d.slowRatioThreshold(), rule::setSlowRatioThreshold);
                RuleFields.ifPresent(d.statIntervalMs(), rule::setStatIntervalMs);
                if (!DegradeRuleManager.isValidRule(rule)) {
                    throw RuleFields.rejected(i, "不是合法的熔断降级规则（Sentinel 的合法性判断未通过：检查 grade、count、timeWindow、minRequestAmount、slowRatioThreshold 与 statIntervalMs）");
                }
                rules.add(rule);
            }
            return rules;
        }
    };

    static final RuleKind<List<ParamFlowRule>> PARAM_FLOW = new ListKind<>(RuleType.PARAM_FLOW, ParamFlowRuleManager::register2Property) {
        @Override
        public List<ParamFlowRule> parse(String content) {
            List<ParamFlowRuleDocument> documents = StrictJson.readList(content, new TypeReference<>() {
            });
            List<ParamFlowRule> rules = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                ParamFlowRuleDocument d = documents.get(i);
                ParamFlowRule rule = new ParamFlowRule(RuleFields.requireText(i, "resource", d.resource()));
                rule.setLimitApp(Constraints.defaultLimitApp(i, d.limitApp()));
                Constraints.requireFalse(i, "clusterMode", d.clusterMode());
                Constraints.requireFalse(i, "regex", d.regex());
                rule.setParamIdx(RuleFields.require(i, "paramIdx", d.paramIdx()));
                rule.setCount(RuleFields.require(i, "count", d.count()));
                RuleFields.ifPresent(d.grade(), rule::setGrade);
                RuleFields.ifPresent(d.controlBehavior(), rule::setControlBehavior);
                RuleFields.ifPresent(d.maxQueueingTimeMs(), rule::setMaxQueueingTimeMs);
                RuleFields.ifPresent(d.burstCount(), rule::setBurstCount);
                RuleFields.ifPresent(d.durationInSec(), rule::setDurationInSec);
                if (d.paramFlowItemList() != null) {
                    List<ParamFlowItem> items = new ArrayList<>(d.paramFlowItemList().size());
                    for (ParamFlowItemDocument item : d.paramFlowItemList()) {
                        if (item == null || item.object() == null || item.count() == null || item.classType() == null) {
                            throw RuleFields.rejected(i, "paramFlowItemList", "的每一项都必须给出 object、count 与 classType");
                        }
                        items.add(new ParamFlowItem(item.object(), item.count(), item.classType()));
                    }
                    rule.setParamFlowItemList(items);
                }
                if (!ParamFlowRuleUtil.isValidRule(rule)) {
                    throw RuleFields.rejected(i, "不是合法的热点参数规则（Sentinel 的合法性判断未通过：检查 paramIdx、count、grade、durationInSec 与 controlBehavior）");
                }
                rules.add(rule);
            }
            return rules;
        }
    };

    static final RuleKind<List<SystemRule>> SYSTEM = new ListKind<>(RuleType.SYSTEM, SystemRuleManager::register2Property) {
        @Override
        public List<SystemRule> parse(String content) {
            List<SystemRuleDocument> documents = StrictJson.readList(content, new TypeReference<>() {
            });
            List<SystemRule> rules = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                SystemRuleDocument d = documents.get(i);
                SystemRule rule = new SystemRule();
                boolean any = false;
                any |= Constraints.threshold(i, "highestSystemLoad", d.highestSystemLoad(), rule::setHighestSystemLoad);
                any |= Constraints.threshold(i, "highestCpuUsage", d.highestCpuUsage(), rule::setHighestCpuUsage);
                any |= Constraints.threshold(i, "qps", d.qps(), rule::setQps);
                any |= Constraints.threshold(i, "avgRt", d.avgRt() == null ? null : d.avgRt().doubleValue(),
                        value -> rule.setAvgRt(d.avgRt()));
                any |= Constraints.threshold(i, "maxThread", d.maxThread() == null ? null : d.maxThread().doubleValue(),
                        value -> rule.setMaxThread(d.maxThread()));
                if (d.highestCpuUsage() != null && d.highestCpuUsage() > 1) {
                    throw RuleFields.rejected(i, "highestCpuUsage", "是 0 到 1 之间的比例，-1 表示不启用");
                }
                if (!any) {
                    throw RuleFields.rejected(i, "没有启用任何阈值（highestSystemLoad、highestCpuUsage、qps、avgRt、maxThread 至少一项不为 -1）");
                }
                rules.add(rule);
            }
            return rules;
        }
    };

    private abstract static class ListKind<R> implements RuleKind<List<R>> {

        private final RuleType type;
        private final Consumer<SentinelProperty<List<R>>> registrar;

        ListKind(RuleType type, Consumer<SentinelProperty<List<R>>> registrar) {
            this.type = type;
            this.registrar = registrar;
        }

        @Override
        public RuleType type() {
            return type;
        }

        @Override
        public void register(SentinelProperty<List<R>> property) {
            registrar.accept(property);
        }

        @Override
        public int size(List<R> rules) {
            return rules.size();
        }
    }

    /** 本项目对服务规则的附加约束。 */
    static final class Constraints {

        private Constraints() {
        }



        static String defaultLimitApp(int index, String limitApp) {
            if (limitApp != null && !RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
                throw RuleFields.rejected(index, "limitApp", "只能是 default：本项目不配置调用来源解析，其他取值永远不会命中");
            }
            return RuleConstant.LIMIT_APP_DEFAULT;
        }

        static void requireFalse(int index, String field, Boolean value) {
            if (Boolean.TRUE.equals(value)) {
                String reason = switch (field) {
                    case "clusterMode" -> "只能是 false：不使用集群限流";
                    case "regex" -> "只能是 false：资源名按字面匹配";
                    default -> "只能是 false";
                };
                throw RuleFields.rejected(index, field, reason);
            }
        }


        /** 系统规则的阈值只能是 -1（不启用）或非负数；返回是否启用。 */
        static boolean threshold(int index, String field, Double value, Consumer<Double> setter) {
            if (value == null || value == -1) {
                return false;
            }
            if (value < 0) {
                throw RuleFields.rejected(index, field, "只能是 -1（不启用）或非负数");
            }
            setter.accept(value);
            return true;
        }
    }
}
