package com.mars.cloud.feign.internal;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureKind;
import feign.Response;
import feign.codec.Decoder;

import java.io.IOException;
import java.lang.reflect.Type;

final class EnvelopeDecoder implements Decoder {

    private final Decoder delegate;
    private final EnvelopeInspector inspector;
    private final DownstreamFailureMapperRegistry mappers;

    EnvelopeDecoder(Decoder delegate, EnvelopeInspector inspector, DownstreamFailureMapperRegistry mappers) {
        this.delegate = delegate;
        this.inspector = inspector;
        this.mappers = mappers;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        byte[] body = ResponseBodies.read(response);
        EnvelopeInspection inspection = inspector.inspect(body);
        if (!inspection.envelope()) {
            throw wrapped(response, DownstreamFailureKind.MALFORMED_RESPONSE, null);
        }
        if (!inspection.success()) {
            throw wrapped(response, DownstreamFailureKind.BUSINESS_ENVELOPE, inspection.code());
        }
        return delegate.decode(response.toBuilder().body(body).build(), type);
    }

    private MappedDownstreamException wrapped(Response response, DownstreamFailureKind kind, String code) {
        RuntimeException mapped;
        try {
            mapped = mappers.map(new DownstreamFailure(
                    FeignRequestFacts.clientName(response.request()),
                    FeignRequestFacts.methodKey(response.request(), null),
                    response.request().httpMethod().name(),
                    FeignRequestFacts.path(response.request()),
                    response.status(),
                    kind,
                    code,
                    null));
        } catch (RuntimeException ex) {
            mapped = ex;
        }
        return new MappedDownstreamException(response.status(), response.request(), mapped);
    }
}
