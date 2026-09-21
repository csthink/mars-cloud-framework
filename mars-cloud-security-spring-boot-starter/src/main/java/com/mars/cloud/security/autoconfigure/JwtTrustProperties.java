package com.mars.cloud.security.autoconfigure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Uses the standard Spring resource server issuer and JWK configuration names. */
@ConfigurationProperties("spring.security.oauth2.resourceserver.jwt")
public class JwtTrustProperties {
    private String issuerUri;
    private String jwkSetUri;
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String value) { issuerUri = value; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String value) { jwkSetUri = value; }
    public void validate(Environment environment) {
        validateUri(issuerUri, environment);
        if (jwkSetUri != null && !jwkSetUri.isBlank()) validateUri(jwkSetUri, environment);
    }
    public static void validateUri(String value, Environment environment) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            boolean allowedHttp = "http".equals(uri.getScheme()) && loopback
                    && environment.acceptsProfiles(Profiles.of("local", "test"));
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || allowedHttp)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Security issuer and JWK endpoints require HTTPS; loopback HTTP is local/test only");
        }
    }
}
