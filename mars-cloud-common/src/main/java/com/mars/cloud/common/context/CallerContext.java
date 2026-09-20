package com.mars.cloud.common.context;

import java.util.Objects;

/**
 * 服务之间调用时携带的已验证调用方身份。
 *
 * <p>本类型只描述身份字段，不负责从令牌解析身份，也不提供默认值。创建方必须先完成令牌验证，
 * 再显式提供三个非空字段。
 *
 * @param subject 已验证令牌的 {@code sub}
 * @param clientId 已验证令牌的 {@code client_id}
 * @param tenantId 租户标识，当前阶段固定为 {@code default}
 * @since 2026-09-20
 */
public record CallerContext(String subject, String clientId, String tenantId) {

    public CallerContext {
        subject = requireText(subject, "subject");
        clientId = requireText(clientId, "clientId");
        tenantId = requireText(tenantId, "tenantId");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
