package com.mars.cloud.feign.internal;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureKind;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.IOException;

final class EnvelopeErrorDecoder implements ErrorDecoder {

    private final EnvelopeInspector inspector;
    private final DownstreamFailureMapperRegistry mappers;

    EnvelopeErrorDecoder(EnvelopeInspector inspector, DownstreamFailureMapperRegistry mappers) {
        this.inspector = inspector;
        this.mappers = mappers;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        byte[] body;
        try {
            body = ResponseBodies.read(response);
        } catch (IOException ex) {
            return map(response, methodKey, DownstreamFailureKind.MALFORMED_RESPONSE, null, ex);
        }

        String clientName = FeignRequestFacts.clientName(response.request());
        if (ResponseBodies.isNoInstanceResponse(response, body, clientName)) {
            return map(response, methodKey, DownstreamFailureKind.UNAVAILABLE, null, null);
        }

        EnvelopeInspection inspection = inspector.inspect(body);
        if (!inspection.envelope() || inspection.success()) {
            return map(response, methodKey, DownstreamFailureKind.MALFORMED_RESPONSE, null, null);
        }
        return map(response, methodKey, DownstreamFailureKind.HTTP, inspection.code(), null);
    }

    private RuntimeException map(Response response,
                                 String methodKey,
                                 DownstreamFailureKind kind,
                                 String code,
                                 Throwable cause) {
        return mappers.map(new DownstreamFailure(
                FeignRequestFacts.clientName(response.request()),
                FeignRequestFacts.methodKey(response.request(), methodKey),
                response.request().httpMethod().name(),
                FeignRequestFacts.path(response.request()),
                response.status(),
                kind,
                code,
                cause));
    }
}
