package com.mars.cloud.feign.internal;

import feign.Client;
import feign.InvocationHandlerFactory;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import tools.jackson.databind.ObjectMapper;

/**
 * Capability 使用的组件工厂。
 */
public final class MarsFeignComponents {

    private final DownstreamFailureMapperRegistry mappers;
    private final EnvelopeInspector inspector;

    public MarsFeignComponents(DownstreamFailureMapperRegistry mappers, ObjectMapper objectMapper) {
        this.mappers = mappers;
        this.inspector = new EnvelopeInspector(objectMapper);
    }

    public Client client(Client delegate) {
        return new FailureMappingClient(delegate, mappers);
    }

    public Decoder decoder(Decoder delegate) {
        return new EnvelopeDecoder(delegate, inspector, mappers);
    }

    public ErrorDecoder errorDecoder() {
        return new EnvelopeErrorDecoder(inspector, mappers);
    }

    public InvocationHandlerFactory invocationHandlerFactory(InvocationHandlerFactory delegate) {
        return new FailureUnwrappingInvocationHandlerFactory(delegate);
    }
}
