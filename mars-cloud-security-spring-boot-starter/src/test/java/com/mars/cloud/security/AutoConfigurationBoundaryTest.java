package com.mars.cloud.security;

import com.mars.cloud.security.autoconfigure.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;

class AutoConfigurationBoundaryTest {
    private final AutoConfigurations configurations = AutoConfigurations.of(ServletSecurityAutoConfiguration.class, ReactiveSecurityAutoConfiguration.class);
    private static final String[] TRUST = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/keys", "mars.security.audience=sample"};
    @Test void noWebCreatesNeitherChainNorDecoder() {
        new ApplicationContextRunner().withConfiguration(configurations).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SecurityFilterChain.class).doesNotHaveBean(SecurityWebFilterChain.class);
            assertThat(context).doesNotHaveBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        });
    }
    @Test void servletWithoutReactiveClassesStarts() {
        new WebApplicationContextRunner().withClassLoader(new FilteredClassLoader("org.springframework.web.reactive", "reactor"))
                .withConfiguration(configurations).withUserConfiguration(ServletHost.class).withPropertyValues(TRUST)
                .withPropertyValues("mars.security.authorization.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class));
    }
    @Test void reactiveWithoutServletClassesStarts() {
        new ReactiveWebApplicationContextRunner().withClassLoader(new FilteredClassLoader("jakarta.servlet", "org.springframework.web.servlet", "feign"))
                .withConfiguration(configurations).withPropertyValues(TRUST).withPropertyValues("mars.security.authorization.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SecurityWebFilterChain.class));
    }
    @Test void servletFailsAtStartupWithoutPermissionAdapter() {
        new WebApplicationContextRunner().withConfiguration(configurations).withUserConfiguration(ServletHost.class).withPropertyValues(TRUST)
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void requiredIssuerAndAudienceFailAtStartup() {
        new WebApplicationContextRunner().withConfiguration(configurations).withUserConfiguration(ServletHost.class)
                .withPropertyValues("mars.security.authorization.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings = {"http://issuer.example", "http://127.0.0.1:1234", "https://user:pass@issuer.example", "file:///keys", "https://issuer.example/#fragment"})
    void rejectsUnsafeProductionTrustEndpoints(String uri) {
        assertThatThrownBy(() -> JwtTrustProperties.validateUri(uri, new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }
    @Test void testProfileAllowsOnlyLoopbackHttp() {
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        JwtTrustProperties.validateUri("http://127.0.0.1:1234/issuer", env);
        assertThatThrownBy(() -> JwtTrustProperties.validateUri("http://issuer.example", env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledAuthorizationStillRefusesAuthenticatedMethodPermission() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "RS256")
                .subject("alice").claim("client_id", "client").claim("tenant_id", "default").build();
        var authentication = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var servlet = new com.mars.cloud.security.servlet.ServletAuthorization(null);
            assertThatThrownBy(() -> servlet.allowed("view", "resource"))
                    .isInstanceOfSatisfying(SecurityFailure.class, failure -> assertThat(failure.code()).isEqualTo(SecurityErrorCode.ACCESS_DENIED));
            var reactive = new com.mars.cloud.security.reactive.ReactiveAuthorization(null);
            assertThatThrownBy(() -> reactive.allowed("view", "resource")
                    .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(authentication)).block())
                    .isInstanceOfSatisfying(SecurityFailure.class, failure -> assertThat(failure.code()).isEqualTo(SecurityErrorCode.ACCESS_DENIED));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc static class ServletHost { }
}
