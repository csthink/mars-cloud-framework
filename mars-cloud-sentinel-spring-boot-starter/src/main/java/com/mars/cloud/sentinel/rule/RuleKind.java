package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.property.SentinelProperty;

/**
 * 一种规则的解析、校验与向 Sentinel 规则管理器的登记。
 *
 * @param <T> 规则管理器接受的集合类型
 * @since 2026-09-25
 */
public interface RuleKind<T> {

    RuleType type();

    /**
     * 解析并校验一批规则；任一条不通过则整批拒绝。
     *
     * @param content 非空白的 JSON 文本
     * @throws RuleRejectedException 结构、Sentinel 的合法性判断或本项目约束不通过
     */
    T parse(String content);

    /** 让对应的 Sentinel 规则管理器订阅这个属性。 */
    void register(SentinelProperty<T> property);

    int size(T rules);
}
