package com.mars.cloud.sentinel.feign;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;

/**
 * 每次调用进入资源 {@code feign:<客户端名>}：传输异常与 5xx 响应计入资源异常，被拦截时不发出请求。
 *
 * @since 2026-09-25
 */
final class SentinelFeignClient implements Client {

    static final String RESOURCE_PREFIX = "feign:";

    private final Client delegate;

    SentinelFeignClient(Client delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        String resource = RESOURCE_PREFIX + clientName(request);
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException blocked) {
            throw new SentinelBlockedCallException(resource, blocked);
        }
        try {
            Response response = delegate.execute(request, options);
            if (response.status() >= 500) {
                Tracer.traceEntry(new DownstreamServerError(response.status()), entry);
            }
            return response;
        } catch (IOException | RuntimeException ex) {
            Tracer.traceEntry(ex, entry);
            throw ex;
        } finally {
            entry.exit();
        }
    }

    private static String clientName(Request request) {
        if (request.requestTemplate() == null || request.requestTemplate().feignTarget() == null) {
            throw new IllegalStateException("Feign 请求没有携带客户端信息，无法确定 Sentinel 资源名");
        }
        return request.requestTemplate().feignTarget().name();
    }

    /** 只用于计入资源异常，不抛出，也不记录堆栈。 */
    static final class DownstreamServerError extends RuntimeException {

        DownstreamServerError(int status) {
            super("下游返回 " + status, null, false, false);
        }
    }
}
