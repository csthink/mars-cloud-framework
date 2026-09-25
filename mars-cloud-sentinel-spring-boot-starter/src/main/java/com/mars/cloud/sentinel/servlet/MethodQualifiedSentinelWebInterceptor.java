package com.mars.cloud.sentinel.servlet;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * 资源名为「HTTP 方法:路径模板」的 Servlet 拦截器，例如 {@code POST:/order/v1/callbacks/{channel}}。
 *
 * <p>Sentinel 1.8.9 的 Spring MVC 6 适配保存了 {@code httpMethodSpecify} 开关，但取资源名时不读它，
 * 同一路径的 GET 与 POST 因此落到同一个资源上。这个子类在开关打开时补上方法前缀；开关由
 * {@code spring.cloud.sentinel.http-method-specify} 控制，本组件默认打开。
 *
 * @since 2026-09-25
 */
public final class MethodQualifiedSentinelWebInterceptor extends SentinelWebInterceptor {

    private final SentinelWebMvcConfig config;

    public MethodQualifiedSentinelWebInterceptor(SentinelWebMvcConfig config) {
        super(config);
        this.config = config;
    }

    @Override
    protected String getResourceName(HttpServletRequest request) {
        String resourceName = super.getResourceName(request);
        if (resourceName == null || !config.isHttpMethodSpecify()) {
            return resourceName;
        }
        return request.getMethod().toUpperCase(Locale.ROOT) + ":" + resourceName;
    }
}
