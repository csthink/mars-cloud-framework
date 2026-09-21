package com.mars.cloud.security.reactive;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.AuthenticatedCaller;
import java.util.List;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** Installs shared JWT, Reactor identity and errors into a host-owned WebFlux security chain. */
public final class MarsReactiveSecurityConfigurer {
    private final ReactiveJwtDecoder decoder;
    private final ReactiveSecurityErrors errors;
    public MarsReactiveSecurityConfigurer(ReactiveJwtDecoder decoder, ReactiveSecurityErrors errors) {
        this.decoder = decoder; this.errors = errors;
    }
    public ServerHttpSecurity configure(ServerHttpSecurity http) {
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtDecoder(decoder)
                        .jwtAuthenticationConverter(token -> Mono.just(new JwtAuthenticationToken(token, List.of()))))
                        .authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterAfter((exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                        .map(context -> AuthenticatedCaller.from(context.getAuthentication()))
                        .map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(caller -> caller.isPresent()
                                ? chain.filter(exchange).contextWrite(context -> context.put(CallerContext.class, caller.get()))
                                : chain.filter(exchange))
                        .onErrorResume(org.springframework.security.access.AccessDeniedException.class,
                                failure -> errors.handle(exchange, failure)), SecurityWebFiltersOrder.AUTHENTICATION);
        return http;
    }
}
