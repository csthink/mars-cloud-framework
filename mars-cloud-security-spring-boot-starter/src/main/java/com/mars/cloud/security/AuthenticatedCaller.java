package com.mars.cloud.security;

import com.mars.cloud.common.context.CallerContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Identity is read exclusively from an authenticated resource server token. */
public final class AuthenticatedCaller {
    private AuthenticatedCaller() { }
    public static JwtAuthenticationToken token(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new SecurityFailure(SecurityErrorCode.TOKEN_MISSING);
        }
        return jwt;
    }
    public static CallerContext from(Authentication authentication) {
        var jwt = token(authentication).getToken();
        return new CallerContext(jwt.getSubject(), jwt.getClaimAsString("client_id"), jwt.getClaimAsString("tenant_id"));
    }
    public static void requireSame(CallerContext caller, Authentication authentication) {
        if (!from(authentication).equals(caller)) throw new SecurityFailure(SecurityErrorCode.CALLER_MISMATCH);
    }
}
