package com.mars.cloud.security.reactive;

import com.mars.cloud.common.context.CallerContext;
import tools.jackson.databind.JsonNode;
import com.mars.cloud.security.*;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;

/** Sends the validated caller token only to the permission service, without retry or redirects. */
public final class WebClientPdpClient implements ReactivePdpClient {
    private final DecisionApi api;
    public interface DecisionApi {
        @PostExchange(PdpProtocol.PATH)
        Mono<JsonNode> decide(@RequestBody PdpProtocol.Request request);
    }
    public WebClientPdpClient(WebClient.Builder builder) {
        WebClient client = builder.baseUrl("http://" + PdpProtocol.SERVICE)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536))
                .filters(filters -> filters.addFirst((request, next) -> ReactiveSecurityContextHolder.getContext()
                        .switchIfEmpty(Mono.error(new SecurityFailure(SecurityErrorCode.TOKEN_MISSING)))
                        .flatMap(context -> {
                            if (!PdpProtocol.SERVICE.equals(request.url().getHost()))
                                return Mono.error(new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR));
                            String token = AuthenticatedCaller.token(context.getAuthentication()).getToken().getTokenValue();
                            return next.exchange(ClientRequest.from(request).headers(headers -> {
                                headers.setBearerAuth(token);
                                var caller = AuthenticatedCaller.from(context.getAuthentication());
                                headers.set(com.mars.cloud.common.context.InternalCallHeaders.SUBJECT, caller.subject());
                                headers.set(com.mars.cloud.common.context.InternalCallHeaders.CLIENT_ID, caller.clientId());
                                headers.set(com.mars.cloud.common.context.InternalCallHeaders.TENANT_ID, caller.tenantId());
                            }).build())
                                    .flatMap(response -> response.statusCode().is2xxSuccessful() ? Mono.just(response)
                                            : response.releaseBody().then(Mono.error(new SecurityFailure(
                                                    response.statusCode().value() == 503 ? SecurityErrorCode.PDP_UNAVAILABLE
                                                            : SecurityErrorCode.PDP_PROTOCOL_ERROR))));
                        }))).build();
        api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client)).build().createClient(DecisionApi.class);
    }
    @Override public Mono<PdpDecision> decide(CallerContext caller, String action, String resource) {
        return Mono.defer(() -> ReactiveSecurityContextHolder.getContext()
                .switchIfEmpty(Mono.error(new SecurityFailure(SecurityErrorCode.TOKEN_MISSING)))
                .flatMap(context -> {
                    AuthenticatedCaller.requireSame(caller, context.getAuthentication());
                    return api.decide(new PdpProtocol.Request(caller.subject(), action, resource));
                }).switchIfEmpty(Mono.error(new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR)))
                .map(PdpProtocol::decision)
                .onErrorMap(error -> {
                    if (error instanceof SecurityFailure) return error;
                    if (error instanceof WebClientResponseException response) {
                        return new SecurityFailure(response.getStatusCode().value() == 503
                                ? SecurityErrorCode.PDP_UNAVAILABLE : SecurityErrorCode.PDP_PROTOCOL_ERROR);
                    }
                    return new SecurityFailure(error instanceof WebClientRequestException
                            ? SecurityErrorCode.PDP_UNAVAILABLE : SecurityErrorCode.PDP_PROTOCOL_ERROR);
                }));
    }
}
