package com.mars.cloud.security.servlet;

import java.util.List;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/** Installs shared JWT, caller context and error handling into a host-owned filter chain. */
public final class MarsServletSecurityConfigurer {
    private final JwtDecoder decoder;
    private final ServletSecurityErrors errors;
    public MarsServletSecurityConfigurer(JwtDecoder decoder, ServletSecurityErrors errors) {
        this.decoder = decoder; this.errors = errors;
    }
    public HttpSecurity configure(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(errors::commence);
                                return filter;
                            }
                        }))
                .addFilterAfter(new CallerContextFilter(), BearerTokenAuthenticationFilter.class);
        return http;
    }
}
