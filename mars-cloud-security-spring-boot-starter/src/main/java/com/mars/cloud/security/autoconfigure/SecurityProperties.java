package com.mars.cloud.security.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Service audience and optional permission client activation. */
@ConfigurationProperties("mars.security")
public class SecurityProperties {
    private String audience;
    private final Authorization authorization = new Authorization();
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public Authorization getAuthorization() { return authorization; }
    public static class Authorization {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
    }
    public void validate() {
        if (audience == null || audience.isBlank() || !audience.equals(audience.strip()))
            throw new IllegalStateException("mars.security.audience must be configured");
    }
}
