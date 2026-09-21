package com.mars.cloud.security.feign;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.*;
import org.springframework.security.core.context.SecurityContextHolder;

/** Blocking adapter with authenticated caller consistency and strict response validation. */
public final class FeignPdpClient implements PdpClient {
    private final PermissionDecisionApi api;
    public FeignPdpClient(PermissionDecisionApi api) { this.api = api; }
    @Override public PdpDecision decide(CallerContext caller, String action, String resource) {
        AuthenticatedCaller.requireSame(caller, SecurityContextHolder.getContext().getAuthentication());
        try (var ignored = com.mars.cloud.common.context.CallerContextHolder.open(caller)) {
            return PdpProtocol.decision(api.decide(new PdpProtocol.Request(caller.subject(), action, resource)));
        }
    }
}
