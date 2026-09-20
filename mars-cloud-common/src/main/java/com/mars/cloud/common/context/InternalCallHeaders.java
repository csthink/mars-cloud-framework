package com.mars.cloud.common.context;

/**
 * 服务之间传递已验证调用方身份时使用的固定请求头名。
 *
 * <p>这些请求头只允许由服务端根据已验证令牌生成。外部请求到达 gateway 时必须先删除同名头，
 * 避免调用方伪造内部身份。
 *
 * @since 2026-09-20
 */
public final class InternalCallHeaders {

    public static final String SUBJECT = "X-Mars-Subject";
    public static final String CLIENT_ID = "X-Mars-Client-Id";
    public static final String TENANT_ID = "X-Mars-Tenant-Id";

    private InternalCallHeaders() {
    }
}
