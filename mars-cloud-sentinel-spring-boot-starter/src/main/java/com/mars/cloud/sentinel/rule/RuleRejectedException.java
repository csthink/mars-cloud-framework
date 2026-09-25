package com.mars.cloud.sentinel.rule;

/**
 * 一批规则没有通过校验。消息写明第几条规则、哪个字段与原因，不回显规则内容。
 *
 * @since 2026-09-25
 */
public final class RuleRejectedException extends RuntimeException {

    public RuleRejectedException(String message) {
        super(message);
    }

    public RuleRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

}
