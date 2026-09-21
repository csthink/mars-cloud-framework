package com.mars.cloud.security.jwt;

import java.time.Clock;
import java.time.Duration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** Validates mandatory identity claims in addition to issuer and token time limits. */
public final class IdentityTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final OAuth2TokenValidator<Jwt> standard;
    private final String audience;
    public IdentityTokenValidator(String issuer, String audience, Clock clock) {
        JwtTimestampValidator timestamp = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamp.setClock(clock);
        standard = new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer), timestamp);
        this.audience = audience;
    }
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!rawIdentityTypes(jwt) || jwt.getExpiresAt() == null || jwt.getAudience() == null || !jwt.getAudience().contains(audience)
                || !canonical(jwt.getClaims().get("sub")) || !canonical(jwt.getClaims().get("client_id"))
                || !"default".equals(jwt.getClaims().get("tenant_id"))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid identity claims", null));
        }
        return standard.validate(jwt);
    }
    private static boolean rawIdentityTypes(Jwt jwt) {
        try {
            // Nimbus can coerce registered sub claims before Spring's claim converter runs.
            var raw = com.nimbusds.jose.JWSObject.parse(jwt.getTokenValue()).getPayload().toJSONObject();
            return canonical(raw.get("sub")) && canonical(raw.get("client_id")) && "default".equals(raw.get("tenant_id"));
        } catch (RuntimeException | java.text.ParseException exception) { return false; }
    }
    private static boolean canonical(Object value) {
        return value instanceof String text && !text.isBlank() && text.equals(text.strip());
    }
}
