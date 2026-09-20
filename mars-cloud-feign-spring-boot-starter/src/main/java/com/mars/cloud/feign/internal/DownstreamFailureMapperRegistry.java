package com.mars.cloud.feign.internal;

import com.mars.cloud.feign.DownstreamFailure;
import com.mars.cloud.feign.DownstreamFailureMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 client 名称索引调用方失败映射器。
 */
public final class DownstreamFailureMapperRegistry {

    private final Map<String, DownstreamFailureMapper> mappers;

    public DownstreamFailureMapperRegistry(List<DownstreamFailureMapper> candidates) {
        Map<String, DownstreamFailureMapper> indexed = new LinkedHashMap<>();
        for (DownstreamFailureMapper candidate : candidates) {
            String clientName = normalizedClientName(candidate.clientName());
            DownstreamFailureMapper previous = indexed.putIfAbsent(clientName, candidate);
            if (previous != null) {
                throw new IllegalStateException("Feign client [" + clientName + "] 只能声明一个 DownstreamFailureMapper");
            }
        }
        this.mappers = Map.copyOf(indexed);
    }

    public RuntimeException map(DownstreamFailure failure) {
        String clientName = normalizedClientName(failure.clientName());
        DownstreamFailureMapper mapper = mappers.get(clientName);
        if (mapper == null) {
            throw new IllegalStateException("Feign client [" + clientName + "] 缺少 DownstreamFailureMapper");
        }
        RuntimeException mapped = mapper.map(failure);
        if (mapped == null) {
            throw new IllegalStateException("Feign client [" + clientName + "] 的 DownstreamFailureMapper 返回了 null");
        }
        return mapped;
    }

    private static String normalizedClientName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalStateException("DownstreamFailureMapper.clientName() 不得为空");
        }
        return clientName.trim();
    }
}
