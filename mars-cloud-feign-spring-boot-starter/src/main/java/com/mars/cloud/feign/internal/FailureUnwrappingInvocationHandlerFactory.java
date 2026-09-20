package com.mars.cloud.feign.internal;

import feign.InvocationHandlerFactory;
import feign.Target;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

final class FailureUnwrappingInvocationHandlerFactory implements InvocationHandlerFactory {

    private final InvocationHandlerFactory delegate;

    FailureUnwrappingInvocationHandlerFactory(InvocationHandlerFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
        InvocationHandler handler = delegate.create(target, dispatch);
        return (proxy, method, args) -> {
            try {
                return handler.invoke(proxy, method, args);
            } catch (MappedDownstreamException ex) {
                throw ex.mapped();
            }
        };
    }
}
