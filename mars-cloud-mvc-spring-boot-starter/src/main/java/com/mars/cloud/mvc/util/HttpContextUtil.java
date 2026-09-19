package com.mars.cloud.mvc.util;

import com.mars.cloud.mvc.domain.HttpContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @since 2025-05-07 14:32
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HttpContextUtil {

    private static final ThreadLocal<HttpContext> HTTP_CONTEXT_THREAD_LOCAL = new ThreadLocal<>();

    public static void create(HttpServletRequest request, HttpServletResponse response) {
        try {
            HttpContext httpContext = new HttpContext(request, response);
            HTTP_CONTEXT_THREAD_LOCAL.set(httpContext);
        } catch (Exception e) {
            log.error("不会对业务造成影响 HttpContextUtil 创建 HttpContext 失败", e);
        }
    }

    public static void remove() throws Exception {
        HTTP_CONTEXT_THREAD_LOCAL.remove();
    }

    public static HttpContext getHttpContext() {
        return HTTP_CONTEXT_THREAD_LOCAL.get();
    }

    public static HttpServletRequest getRequest() {
        HttpContext httpContext = HTTP_CONTEXT_THREAD_LOCAL.get();
        if (httpContext != null && httpContext.getRequest() != null) {
            return httpContext.getRequest();
        }

        return null;
    }

    public static HttpServletResponse getResponse() {
        HttpContext httpContext = HTTP_CONTEXT_THREAD_LOCAL.get();
        if (httpContext != null && httpContext.getResponse() != null) {
            return httpContext.getResponse();
        }

        return null;
    }
}
