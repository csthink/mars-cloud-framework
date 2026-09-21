package com.mars.cloud.common.http;

/**
 * 判断响应详情与日志中应省略值的标准凭据请求头。
 *
 * <p>本工具只判断头名，不读取或记录头值，也不自动过滤请求。
 * 自定义凭据头仍须由调用方另外处理。
 */
public final class SensitiveHttpHeaders {

    private SensitiveHttpHeaders() {
    }

    /**
     * 大小写不敏感地识别 Authorization、Proxy-Authorization、Cookie、Set-Cookie 与 X-Api-Key。
     *
     * @param name HTTP 头名；允许为 null
     * @return 已知凭据头返回 true；null、空字符串与其他头名返回 false
     */
    public static boolean isSensitive(String name) {
        return "Authorization".equalsIgnoreCase(name)
                || "Proxy-Authorization".equalsIgnoreCase(name)
                || "Cookie".equalsIgnoreCase(name)
                || "Set-Cookie".equalsIgnoreCase(name)
                || "X-Api-Key".equalsIgnoreCase(name);
    }
}
