package com.mars.cloud.security.reactive;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.SecurityErrorCode;
import com.mars.cloud.security.SecurityFailure;
import reactor.core.publisher.Mono;

/** Subscriber-local identity, including across scheduler changes. */
public final class ReactiveCallerContext {
    private ReactiveCallerContext() { }
    public static Mono<CallerContext> current() {
        return Mono.deferContextual(context -> context.hasKey(CallerContext.class)
                ? Mono.just(context.get(CallerContext.class))
                : Mono.error(new SecurityFailure(SecurityErrorCode.TOKEN_MISSING)));
    }
}
