package com.mars.cloud.feign.internal;

import com.mars.cloud.feign.DownstreamFailure;
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
        try {
            return delegate.execute(request, options);
        } catch (IOException ex) {
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

    private static boolean isTimeout(Throwable failure) {
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
