package com.mars.cloud.feign.internal;

import feign.Request;
import feign.RequestTemplate;

import java.net.URI;

/**
 * 从 Feign 请求提取允许进入失败对象的最小事实。
 */
final class FeignRequestFacts {

    private FeignRequestFacts() {
    }

    static String clientName(Request request) {
        RequestTemplate template = request.requestTemplate();
        if (template != null && template.feignTarget() != null) {
            return template.feignTarget().name();
        }
        URI uri = URI.create(request.url());
        return uri.getHost();
    }

    static String methodKey(Request request, String fallback) {
        RequestTemplate template = request.requestTemplate();
        if (template != null && template.methodMetadata() != null) {
            return template.methodMetadata().configKey();
        }
        return fallback;
    }

    static String path(Request request) {
        URI uri = URI.create(request.url());
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }
}
