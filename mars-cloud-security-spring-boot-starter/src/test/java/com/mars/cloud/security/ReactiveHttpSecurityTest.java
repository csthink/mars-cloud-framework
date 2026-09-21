package com.mars.cloud.security;

import com.mars.cloud.security.reactive.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReactiveHttpSecurityTest extends HttpSecurityContract {
    protected Class<?> application() { return App.class; }
    protected WebApplicationType stack() { return WebApplicationType.REACTIVE; }
    @org.junit.jupiter.api.Test void fluxMethodsUseTheSamePermissionGuard() throws Exception {
        org.assertj.core.api.Assertions.assertThat(get("/stream/allow", signedToken()).statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(get("/stream/deny", signedToken()).statusCode()).isEqualTo(403);
    }
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @Import(Endpoints.class)
    static class App {
        @Bean ReactivePdpClient pdp() { return (caller, action, resource) -> Mono.fromSupplier(() -> decision(action)); }
    }
    @RestController
    public static class Endpoints {
        @GetMapping("/me") public Mono<Map<String, Object>> me() {
            return ReactiveCallerContext.current().flatMap(caller -> ReactiveSecurityContextHolder.getContext()
                    .map(context -> Map.of("subject", caller.subject(), "authorities", context.getAuthentication().getAuthorities().stream().map(Object::toString).toList())));
        }
        @GetMapping("/method/{action}") @PreAuthorize("@marsAuthorization.allowed(#action,'resource')")
        public Mono<Map<String, Boolean>> guarded(@PathVariable String action) {
            INVOCATIONS.incrementAndGet(); return Mono.just(Map.of("allowed", true));
        }
        @GetMapping("/stream/{action}") @PreAuthorize("@marsAuthorization.allowed(#action,'resource')")
        public reactor.core.publisher.Flux<Map<String, Boolean>> stream(@PathVariable String action) {
            return reactor.core.publisher.Flux.just(Map.of("allowed", true));
        }
        @GetMapping("/async") public Mono<Map<String, String>> async() {
            return Mono.delay(Duration.ofMillis(5)).publishOn(Schedulers.boundedElastic())
                    .flatMap(ignored -> ReactiveCallerContext.current()).map(caller -> Map.of("subject", caller.subject()));
        }
    }
}
