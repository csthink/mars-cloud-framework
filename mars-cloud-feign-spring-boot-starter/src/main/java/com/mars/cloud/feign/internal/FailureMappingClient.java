package com.mars.cloud.feign.internal;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.feign.autoconfigure.FeignConventionVerifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.mars.cloud.feign.DownstreamFailureKind;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

final class FailureMappingClient implements Client {

    private final Client delegate;
    private final DownstreamFailureMapperRegistry mappers;

    FailureMappingClient(Client delegate, DownstreamFailureMapperRegistry mappers) {
        this.delegate = delegate;
        this.mappers = mappers;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        FeignConventionVerifier.verifyOptions(options);
        try (ServiceInstanceRetryGuard.Scope ignored = ServiceInstanceRetryGuard.open()) {
            return delegate.execute(trustedRequest(request), options);
        } catch (IOException | ServiceInstanceRetryGuard.RepeatedInstanceException ex) {
            throw mappers.map(new DownstreamFailure(
                    FeignRequestFacts.clientName(request),
                    FeignRequestFacts.methodKey(request, null),
                    request.httpMethod().name(),
                    FeignRequestFacts.path(request),
                    null,
                    isTimeout(ex) ? DownstreamFailureKind.TIMEOUT : DownstreamFailureKind.UNAVAILABLE,
                    null,
                    ex));
        }
    }

    private static Request trustedRequest(Request request) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(request.headers());
        headers.remove(InternalCallHeaders.SUBJECT);
        headers.remove(InternalCallHeaders.CLIENT_ID);
        headers.remove(InternalCallHeaders.TENANT_ID);
        CallerContextHolder.current().ifPresent(context -> {
            headers.put(InternalCallHeaders.SUBJECT, List.of(context.subject()));
            headers.put(InternalCallHeaders.CLIENT_ID, List.of(context.clientId()));
            headers.put(InternalCallHeaders.TENANT_ID, List.of(context.tenantId()));
        });
        return Request.create(request.httpMethod(), request.url(), headers, request.body(),
                request.charset(), request.requestTemplate());
    }

    static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
