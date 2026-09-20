package com.mars.cloud.feign.internal;

import feign.FeignException;
import feign.Request;

/**
 * 让 Feign 解码层保留调用方异常类型，随后由 invocation handler 解包。
 */
final class MappedDownstreamException extends FeignException {

    private final RuntimeException mapped;

    MappedDownstreamException(int status, Request request, RuntimeException mapped) {
        super(status, mapped.getMessage(), request, mapped);
        this.mapped = mapped;
    }

    RuntimeException mapped() {
        return mapped;
    }
}
