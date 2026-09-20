package com.mars.cloud.feign;

import com.mars.cloud.feign.internal.DownstreamFailureMapperRegistry;
import com.mars.cloud.feign.internal.MarsFeignComponents;
import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Retryer;
import com.mars.cloud.feign.autoconfigure.FeignConventionVerifier;
import feign.InvocationHandlerFactory;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;

/**
 * 在 Feign 构建完成时装饰最终客户端、解码器与错误解码器。
 *
 * <p>类必须公开，OpenFeign 通过反射调用 {@code enrich} 方法。
 */
public final class MarsFeignCapability implements Capability {

    private final MarsFeignComponents components;

    public MarsFeignCapability(DownstreamFailureMapperRegistry mappers, tools.jackson.databind.ObjectMapper objectMapper) {
        this.components = new MarsFeignComponents(mappers, objectMapper);
    }

    @Override
    public Retryer enrich(Retryer retryer) {
        FeignConventionVerifier.verifyRetryer(retryer);
        return retryer;
    }

    @Override
    public Request.Options enrich(Request.Options options) {
        FeignConventionVerifier.verifyOptions(options);
        return options;
    }

    @Override
    public Client enrich(Client client) {
        return components.client(client);
    }

    @Override
    public Decoder enrich(Decoder decoder) {
        return components.decoder(decoder);
    }

    @Override
    public ErrorDecoder enrich(ErrorDecoder errorDecoder) {
        return components.errorDecoder();
    }

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory invocationHandlerFactory) {
        return components.invocationHandlerFactory(invocationHandlerFactory);
    }
}
