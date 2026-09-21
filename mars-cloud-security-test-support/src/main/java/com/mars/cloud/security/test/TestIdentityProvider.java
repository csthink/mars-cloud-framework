package com.mars.cloud.security.test;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-test RSA issuer and rotating public JWKS endpoint. Never use as a production identity provider. */
public final class TestIdentityProvider implements AutoCloseable {
    private final HttpServer server;
    private volatile RSAKey signingKey;
    private volatile List<RSAKey> publicKeys;
    private volatile int jwksStatus = 200;
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger discoveryRequests = new AtomicInteger();

    public TestIdentityProvider() {
        try {
            rotate(false);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                int status = 200;
                if (path.equals("/keys")) {
                    jwksRequests.incrementAndGet();
                    status = jwksStatus;
                    body = new JWKSet(new ArrayList<JWK>(publicKeys)).toString().getBytes(StandardCharsets.UTF_8);
                } else if (path.contains(".well-known")) {
                    discoveryRequests.incrementAndGet();
                    body = ("{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + jwksUri()
                            + "\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}").getBytes(StandardCharsets.UTF_8);
                } else { status = 404; body = new byte[0]; }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            server.start();
        } catch (Exception exception) { throw new IllegalStateException("Cannot start test issuer", exception); }
    }
    public String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer"; }
    public String jwksUri() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/keys"; }
    public int jwksRequests() { return jwksRequests.get(); }
    public int discoveryRequests() { return discoveryRequests.get(); }
    public void jwksStatus(int status) { jwksStatus = status; }
    public synchronized void rotate(boolean retainPrevious) {
        try {
            var key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            var keys = new ArrayList<RSAKey>();
            keys.add(key.toPublicJWK());
            if (retainPrevious && publicKeys != null) keys.addAll(publicKeys);
            publicKeys = List.copyOf(keys);
            signingKey = key;
        } catch (JOSEException exception) { throw new IllegalStateException("Cannot generate test signing key", exception); }
    }
    public Map<String, Object> claims(String subject, String... audiences) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer()); claims.put("sub", subject); claims.put("aud", List.of(audiences));
        claims.put("client_id", "test-client"); claims.put("tenant_id", "default");
        claims.put("iat", new Date()); claims.put("exp", Date.from(Instant.now().plusSeconds(300)));
        return claims;
    }
    public String token(String subject, String... audiences) { return sign(claims(subject, audiences)); }
    public String sign(Map<String, Object> claims) { return sign(claims, JWSAlgorithm.RS256); }
    public String sign(Map<String, Object> claims, JWSAlgorithm algorithm) {
        return sign(claims, new JWSHeader.Builder(algorithm).keyID(signingKey.getKeyID()).build());
    }
    public String signWithKeyReferences(Map<String, Object> claims, URI reference) {
        return sign(claims, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                .jwkURL(reference).x509CertURL(reference).build());
    }
    private String sign(Map<String, Object> claims, JWSHeader header) {
        try {
            RSAKey key = signingKey;
            var payload = new LinkedHashMap<>(claims);
            payload.replaceAll((name, value) -> value instanceof Date date ? date.toInstant().getEpochSecond() : value);
            var jwt = new JWSObject(header,
                    new Payload(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(payload)));
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) { throw new IllegalArgumentException("Cannot sign test token", exception); }
    }
    @Override public void close() { server.stop(0); }
}
