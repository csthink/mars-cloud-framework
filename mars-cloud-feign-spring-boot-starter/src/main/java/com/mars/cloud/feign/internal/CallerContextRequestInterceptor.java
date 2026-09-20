package com.mars.cloud.feign.internal;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * 把当前阻塞线程的调用方身份写入内部请求头。
 */
public final class CallerContextRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        template.removeHeader(InternalCallHeaders.SUBJECT);
        template.removeHeader(InternalCallHeaders.CLIENT_ID);
        template.removeHeader(InternalCallHeaders.TENANT_ID);

        CallerContextHolder.current().ifPresent(context -> write(template, context));
    }

    private static void write(RequestTemplate template, CallerContext context) {
        template.header(InternalCallHeaders.SUBJECT, context.subject());
        template.header(InternalCallHeaders.CLIENT_ID, context.clientId());
        template.header(InternalCallHeaders.TENANT_ID, context.tenantId());
    }
}
