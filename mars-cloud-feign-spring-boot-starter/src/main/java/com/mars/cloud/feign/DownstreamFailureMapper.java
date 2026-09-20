package com.mars.cloud.feign;

/**
 * 把下游失败翻译成当前服务自己的异常和错误码。
 *
 * <p>每个 Feign client 必须有且只能有一个同名 mapper。返回 {@code null} 会被视为配置错误。
 */
public interface DownstreamFailureMapper {

    /**
     * 与 {@code @FeignClient(name = ...)} 完全一致的 client 名称。
     */
    String clientName();

    /**
     * 返回调用方自己的异常。
     */
    RuntimeException map(DownstreamFailure failure);
}
