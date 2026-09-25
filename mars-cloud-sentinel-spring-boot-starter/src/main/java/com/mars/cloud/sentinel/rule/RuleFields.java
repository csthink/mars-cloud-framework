package com.mars.cloud.sentinel.rule;

import java.util.function.Consumer;

/**
 * 各规则类型共用的字段检查；失败时抛出带位置的 {@link RuleRejectedException}。
 *
 * @since 2026-09-25
 */
public final class RuleFields {

    private RuleFields() {
    }

    public static String requireText(int index, String field, String value) {
        if (value == null || value.isBlank()) {
            throw rejected(index, field, "不能为空");
        }
        return value;
    }

    public static <V> V require(int index, String field, V value) {
        if (value == null) {
            throw rejected(index, field, "必须给出");
        }
        return value;
    }

    public static <V> void ifPresent(V value, Consumer<V> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /** 第 {@code index} 条（从 0 计）规则的某个字段不通过。 */
    public static RuleRejectedException rejected(int index, String field, String reason) {
        return new RuleRejectedException("第 " + (index + 1) + " 条规则的 " + field + " " + reason);
    }

    /** 第 {@code index} 条（从 0 计）规则整体不通过。 */
    public static RuleRejectedException rejected(int index, String reason) {
        return new RuleRejectedException("第 " + (index + 1) + " 条规则" + reason);
    }
}
