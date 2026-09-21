package com.mars.cloud.security.reactive;

import com.mars.cloud.security.*;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/** Resolves the current subscriber's verified identity for each method invocation. */
public final class ReactiveAuthorization {
    private final ReactivePdpClient client;
    public ReactiveAuthorization(ReactivePdpClient client) { this.client = client; }
    public Mono<Boolean> allowed(String action, String resource) {
        return Mono.defer(() -> ReactiveSecurityContextHolder.getContext()
                .switchIfEmpty(Mono.error(new SecurityFailure(SecurityErrorCode.TOKEN_MISSING)))
                .flatMap(context -> {
                    var caller = AuthenticatedCaller.from(context.getAuthentication());
                    if (client == null) return Mono.error(new SecurityFailure(SecurityErrorCode.ACCESS_DENIED));
                    return client.decide(caller, action, resource).map(PdpDecision::allowed);
                }));
    }
}
