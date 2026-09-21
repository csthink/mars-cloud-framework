package com.mars.cloud.security;

import com.mars.cloud.security.autoconfigure.*;
import com.mars.cloud.security.jwt.IdentityTokenValidator;
import com.mars.cloud.security.test.TestIdentityProvider;
import com.nimbusds.jose.JWSAlgorithm;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.*;

class JwtValidationContractTest {
    static Stream<Arguments> invalidClaims() {
        return Stream.of(false, true).flatMap(reactive -> Stream.of("iss", "aud", "sub", "client_id", "tenant_id", "exp",
                "sub-space", "sub-number", "client-space", "client-number", "tenant-other", "expired", "future-nbf", "wrong-iss", "wrong-aud")
                .map(name -> Arguments.of(reactive, name)));
    }
    private Function<String, Jwt> decoder(TestIdentityProvider issuer, boolean reactive, boolean discovery) {
        var properties = new SecurityProperties(); var trust = new JwtTrustProperties();
        trust.setIssuerUri(issuer.issuer()); properties.setAudience("sample");
        if (!discovery) trust.setJwkSetUri(issuer.jwksUri());
        var environment = new MockEnvironment(); environment.setActiveProfiles("test");
        if (reactive) {
            var decoder = new ReactiveSecurityAutoConfiguration().marsReactiveJwtDecoder(properties, trust, environment);
            return token -> decoder.decode(token).block(Duration.ofSeconds(8));
        }
        var decoder = new ServletSecurityAutoConfiguration().marsJwtDecoder(properties, trust, environment);
        return decoder::decode;
    }
    @ParameterizedTest @MethodSource("invalidClaims")
    void rejectsMalformedIdentity(boolean reactive, String defect) {
        try (var issuer = new TestIdentityProvider()) {
            var claims = issuer.claims("alice", "sample");
            switch (defect) {
                case "sub-space" -> claims.put("sub", " alice");
                case "sub-number" -> claims.put("sub", 123);
                case "client-space" -> claims.put("client_id", "client ");
                case "client-number" -> claims.put("client_id", 123);
                case "tenant-other" -> claims.put("tenant_id", "tenant-2");
                case "expired" -> claims.put("exp", Date.from(Instant.now().minusSeconds(120)));
                case "future-nbf" -> claims.put("nbf", Date.from(Instant.now().plusSeconds(120)));
                case "wrong-iss" -> claims.put("iss", "https://other.example");
                case "wrong-aud" -> claims.put("aud", List.of("other"));
                default -> claims.remove(defect);
            }
            var decode = decoder(issuer, reactive, false);
            assertThatThrownBy(() -> decode.apply(issuer.sign(claims))).isInstanceOf(RuntimeException.class);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsMultipleAudiencesAndIgnoresPrivilegeClaims(boolean reactive) {
        try (var issuer = new TestIdentityProvider()) {
            var claims = issuer.claims("alice", "sample", "upms");
            claims.put("roles", List.of("admin")); claims.put("scope", "everything");
            assertThat(decoder(issuer, reactive, false).apply(issuer.sign(claims)).getSubject()).isEqualTo("alice");
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyTrustsRs256AndTrustedKeys(boolean reactive) throws Exception {
        try (var issuer = new TestIdentityProvider(); var attacker = new TestIdentityProvider()) {
            var decode = decoder(issuer, reactive, false);
            var claims = com.nimbusds.jwt.SignedJWT.parse(issuer.token("alice", "sample")).getJWTClaimsSet();
            String unsigned = new com.nimbusds.jwt.PlainJWT(claims).serialize();
            var symmetric = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(JWSAlgorithm.HS256), claims);
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            symmetric.sign(new com.nimbusds.jose.crypto.MACSigner(key));
            assertThatThrownBy(() -> decode.apply(unsigned)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> decode.apply(symmetric.serialize())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> decode.apply(issuer.sign(issuer.claims("alice", "sample"), JWSAlgorithm.RS512))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> decode.apply(attacker.sign(issuer.claims("alice", "sample")))).isInstanceOf(RuntimeException.class);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cachesKeysAndRefreshesOnRotation(boolean reactive) {
        try (var issuer = new TestIdentityProvider()) {
            var decode = decoder(issuer, reactive, false);
            String old = issuer.token("alice", "sample");
            decode.apply(old); int initial = issuer.jwksRequests();
            decode.apply(old); assertThat(issuer.jwksRequests()).isEqualTo(initial);
            issuer.rotate(true);
            assertThat(decode.apply(issuer.token("bob", "sample")).getSubject()).isEqualTo("bob");
            assertThat(issuer.jwksRequests()).isGreaterThan(initial);
            decode.apply(old);
            issuer.rotate(false); issuer.jwksStatus(503);
            assertThatThrownBy(() -> decode.apply(issuer.token("eve", "sample"))).isInstanceOf(RuntimeException.class);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void usesIssuerMetadataWhenJwksUriAbsent(boolean reactive) {
        try (var issuer = new TestIdentityProvider()) {
            assertThat(decoder(issuer, reactive, true).apply(issuer.token("alice", "sample")).getSubject()).isEqualTo("alice");
            assertThat(issuer.discoveryRequests()).isPositive();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ignoresTokenSuppliedKeyLocations(boolean reactive) {
        try (var issuer = new TestIdentityProvider(); var attacker = new TestIdentityProvider()) {
            String token = issuer.signWithKeyReferences(issuer.claims("alice", "sample"), java.net.URI.create(attacker.jwksUri()));
            assertThat(decoder(issuer, reactive, false).apply(token).getSubject()).isEqualTo("alice");
            assertThat(attacker.jwksRequests()).isZero();
        }
    }
    @Test void timestampValidationUsesSixtySecondSkew() {
        try (var issuer = new TestIdentityProvider()) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var validator = new IdentityTokenValidator("https://issuer.example", "sample", Clock.fixed(now, ZoneOffset.UTC));
        for (int seconds : new int[]{-59, -61, 59, 61}) {
            var jwt = Jwt.withTokenValue(issuer.token("alice", "sample")).header("alg", "RS256").issuer("https://issuer.example")
                    .subject("alice").audience(List.of("sample")).claim("client_id", "client").claim("tenant_id", "default")
                    .issuedAt(now.minusSeconds(600)).expiresAt(seconds < 0 ? now.plusSeconds(seconds) : now.plusSeconds(600))
                    .notBefore(seconds > 0 ? now.plusSeconds(seconds) : now.minusSeconds(600)).build();
            assertThat(validator.validate(jwt).hasErrors()).isEqualTo(Math.abs(seconds) > 60);
        }
        }
    }
}
