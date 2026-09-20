package com.mars.cloud.feign.internal;

import feign.Request;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;

import java.util.HashSet;
import java.util.Set;

/** 阻止一次阻塞调用的重试再次发往同一实例。 */
public final class ServiceInstanceRetryGuard implements LoadBalancerFeignRequestTransformer {

    private static final ThreadLocal<Set<String>> ATTEMPTED = new ThreadLocal<>();

    @Override
    public Request transformRequest(Request request, ServiceInstance instance) {
        Set<String> attempted = ATTEMPTED.get();
        if (attempted != null && !attempted.add(instance.getUri().toString())) {
            throw new RepeatedInstanceException();
        }
        return request;
    }

    static Scope open() {
        Set<String> previous = ATTEMPTED.get();
        ATTEMPTED.set(new HashSet<>());
        return () -> {
            if (previous == null) {
                ATTEMPTED.remove();
            } else {
                ATTEMPTED.set(previous);
            }
        };
    }

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    static final class RepeatedInstanceException extends RuntimeException {
        RepeatedInstanceException() {
            super("LoadBalancer 没有其他可重试实例");
        }
    }
}
