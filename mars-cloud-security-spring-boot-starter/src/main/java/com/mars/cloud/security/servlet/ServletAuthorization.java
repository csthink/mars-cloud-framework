package com.mars.cloud.security.servlet;

import com.mars.cloud.security.*;
import org.springframework.security.core.context.SecurityContextHolder;

/** Method expressions always use the authenticated caller. */
public final class ServletAuthorization {
    private final PdpClient client;
    public ServletAuthorization(PdpClient client) { this.client = client; }
    public boolean allowed(String action, String resource) {
        var caller = AuthenticatedCaller.from(SecurityContextHolder.getContext().getAuthentication());
        if (client == null) throw new SecurityFailure(SecurityErrorCode.ACCESS_DENIED);
        return client.decide(caller, action, resource).allowed();
    }
}
