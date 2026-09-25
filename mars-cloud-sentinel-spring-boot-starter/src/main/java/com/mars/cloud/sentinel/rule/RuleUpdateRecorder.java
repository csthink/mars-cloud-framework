package com.mars.cloud.sentinel.rule;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * 规则来源状态的记录方式。有 Micrometer 时是指标，否则不记录；拒绝时的 ERROR 日志不依赖它。
 *
 * @since 2026-09-25
 */
public interface RuleUpdateRecorder {

    RuleUpdateRecorder NONE = new RuleUpdateRecorder() {
        @Override
        public void watch(RuleType type, IntSupplier activeRules, BooleanSupplier lastUpdateAccepted) {
        }

        @Override
        public void accepted(RuleType type) {
        }

        @Override
        public void rejected(RuleType type) {
        }
    };

    /** 登记一种规则的当前状态：生效条数与最近一次更新是否被接受。 */
    void watch(RuleType type, IntSupplier activeRules, BooleanSupplier lastUpdateAccepted);

    void accepted(RuleType type);

    void rejected(RuleType type);
}
