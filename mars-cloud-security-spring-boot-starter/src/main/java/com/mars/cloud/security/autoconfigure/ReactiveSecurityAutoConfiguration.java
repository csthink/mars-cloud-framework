package com.mars.cloud.security.autoconfigure;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.*;
import com.mars.cloud.security.jwt.*;
import com.mars.cloud.security.reactive.*;
import com.mars.cloud.security.web.SecurityResponses;
import io.netty.channel.ChannelOption;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Stateless WebFlux resource server; Servlet and blocking adapters are not required. */
@AutoConfiguration(beforeName = {"org.springframework.boot.security.autoconfigure.web.reactive.ReactiveSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = {"org.springframework.web.reactive.DispatcherHandler", "reactor.netty.http.client.HttpClient"})
@EnableConfigurationProperties({SecurityProperties.class, JwtTrustProperties.class})
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@Import(ReactivePdpAutoConfiguration.class)
public class ReactiveSecurityAutoConfiguration {
    public static ReactorClientHttpConnector connector() {
        return new ReactorClientHttpConnector(HttpClient.create().disableRetry(true).followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(Duration.ofSeconds(3)));
    }
    @Bean
    public ReactiveJwtDecoder marsReactiveJwtDecoder(SecurityProperties properties, JwtTrustProperties trust, Environment environment) {
        properties.validate();
        trust.validate(environment);
        var web = WebClient.builder().clientConnector(connector()).filter((request, next) -> {
            JwtTrustProperties.validateUri(request.url().toString(), environment);
            return next.exchange(request);
        }).build();
        var builder = trust.getJwkSetUri() == null || trust.getJwkSetUri().isBlank()
                ? NimbusReactiveJwtDecoder.withIssuerLocation(trust.getIssuerUri())
                : NimbusReactiveJwtDecoder.withJwkSetUri(trust.getJwkSetUri());
        var decoder = builder.webClient(web).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new IdentityTokenValidator(trust.getIssuerUri(), properties.getAudience(), Clock.systemUTC()));
        return token -> decoder.decode(token).onErrorMap(error -> new BadJwtException("Invalid bearer token"));
    }
    @Bean ReactiveSecurityErrors marsReactiveSecurityErrors(MessageSource messages) {
        return new ReactiveSecurityErrors(new SecurityResponses(messages));
    }
    @Bean(name = "marsAuthorization")
    ReactiveAuthorization marsAuthorization(SecurityProperties properties, ObjectProvider<ReactivePdpClient> clients) {
        var client = properties.getAuthorization().isEnabled() ? clients.getIfAvailable() : null;
        if (properties.getAuthorization().isEnabled() && client == null)
            throw new IllegalStateException("Enabled Reactive authorization requires a ReactivePdpClient adapter");
        return new ReactiveAuthorization(client);
    }
    @Bean
    MarsReactiveSecurityConfigurer marsReactiveSecurityConfigurer(ReactiveJwtDecoder decoder, ReactiveSecurityErrors errors) {
        return new MarsReactiveSecurityConfigurer(decoder, errors);
    }
    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    SecurityWebFilterChain marsSecurityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder, ReactiveSecurityErrors errors) {
        http.csrf(csrf -> csrf.disable()).httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.disable())
                .authorizeExchange(exchanges -> exchanges.pathMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll().anyExchange().authenticated());
        new MarsReactiveSecurityConfigurer(decoder, errors).configure(http);
        return http.build();
    }
}
