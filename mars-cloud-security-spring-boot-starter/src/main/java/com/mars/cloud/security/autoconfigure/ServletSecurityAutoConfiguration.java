package com.mars.cloud.security.autoconfigure;

import com.mars.cloud.security.*;
import com.mars.cloud.security.jwt.IdentityTokenValidator;
import com.mars.cloud.security.servlet.*;
import com.mars.cloud.security.web.SecurityResponses;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/** Stateless Servlet resource server with strict JWT validation. */
@AutoConfiguration(beforeName = {"org.springframework.boot.security.autoconfigure.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.servlet.DispatcherServlet"})
@EnableConfigurationProperties({SecurityProperties.class, JwtTrustProperties.class})
@EnableWebSecurity
@EnableMethodSecurity
public class ServletSecurityAutoConfiguration {
    @Bean
    public JwtDecoder marsJwtDecoder(SecurityProperties properties, JwtTrustProperties trust, Environment environment) {
        properties.validate();
        trust.validate(environment);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        var rest = new RestTemplate(factory);
        rest.getInterceptors().add((request, body, execution) -> {
            JwtTrustProperties.validateUri(request.getURI().toString(), environment);
            return execution.execute(request, body);
        });
        var builder = trust.getJwkSetUri() == null || trust.getJwkSetUri().isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(trust.getIssuerUri())
                : NimbusJwtDecoder.withJwkSetUri(trust.getJwkSetUri());
        var decoder = builder.restOperations(rest).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new IdentityTokenValidator(trust.getIssuerUri(), properties.getAudience(), Clock.systemUTC()));
        return decoder;
    }
    @Bean ServletSecurityErrors marsServletSecurityErrors(MessageSource messages) {
        return new ServletSecurityErrors(new SecurityResponses(messages));
    }
    @Bean ServletSecurityAdvice marsServletSecurityAdvice(ServletSecurityErrors errors) { return new ServletSecurityAdvice(errors); }
    @Bean(name = "marsAuthorization")
    ServletAuthorization marsAuthorization(SecurityProperties properties, ObjectProvider<PdpClient> clients) {
        var client = properties.getAuthorization().isEnabled() ? clients.getIfAvailable() : null;
        if (properties.getAuthorization().isEnabled() && client == null)
            throw new IllegalStateException("Enabled Servlet authorization requires a PdpClient adapter");
        return new ServletAuthorization(client);
    }
    @Bean
    MarsServletSecurityConfigurer marsServletSecurityConfigurer(JwtDecoder decoder, ServletSecurityErrors errors) {
        return new MarsServletSecurityConfigurer(decoder, errors);
    }
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain marsSecurityFilterChain(HttpSecurity http, JwtDecoder decoder, ServletSecurityErrors errors) throws Exception {
        http.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll().anyRequest().authenticated());
        new MarsServletSecurityConfigurer(decoder, errors).configure(http);
        return http.build();
    }
}
