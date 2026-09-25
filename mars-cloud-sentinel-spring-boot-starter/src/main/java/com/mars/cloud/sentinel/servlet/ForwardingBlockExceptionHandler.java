package com.mars.cloud.sentinel.servlet;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 不写响应，把拦截异常抛回 Spring MVC 的异常处理链，由应用的统一异常处理写响应。
 *
 * @since 2026-09-25
 */
public final class ForwardingBlockExceptionHandler implements BlockExceptionHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName,
                       BlockException blocked) throws BlockException {
        throw blocked;
    }
}
