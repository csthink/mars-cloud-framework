package com.mars.cloud.security.reactive;

import com.mars.cloud.security.*;
import com.mars.cloud.security.web.SecurityResponses;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Nonblocking resource server error envelope writer. */
public final class ReactiveSecurityErrors implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {
    private final SecurityResponses responses;
    public ReactiveSecurityErrors(SecurityResponses responses) { this.responses = responses; }
    @Override public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException failure) {
        return write(exchange, exchange.getRequest().getHeaders().containsHeader("Authorization")
                ? SecurityErrorCode.TOKEN_INVALID : SecurityErrorCode.TOKEN_MISSING);
    }
    @Override public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException failure) {
        return write(exchange, SecurityFailure.find(failure));
    }
    public Mono<Void> write(ServerWebExchange exchange, SecurityErrorCode code) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(code.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (code.status() == 401) response.getHeaders().set("WWW-Authenticate", SecurityResponses.challenge(code));
        var locale = exchange.getLocaleContext().getLocale();
        byte[] body = responses.body(code, locale == null ? java.util.Locale.ROOT : locale);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
