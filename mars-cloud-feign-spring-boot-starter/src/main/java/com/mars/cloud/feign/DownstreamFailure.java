package com.mars.cloud.feign;

/**
 * 交给调用方映射的下游失败事实。
 *
 * <p>该对象刻意不保存下游消息或原始响应体，避免内部信息沿服务调用链向外泄露。
 *
 * @param clientName Feign client 名称，同时也是服务发现 ID
 * @param methodKey Feign 方法键
 * @param httpMethod HTTP 方法
 * @param path 不含主机名与查询参数的请求路径
 * @param httpStatus HTTP 状态；传输失败时为空
 * @param kind 稳定失败分类
 * @param downstreamCode 下游信封错误码；没有可信失败信封时为空
 * @param cause 传输异常；HTTP 响应失败时通常为空
 */
public record DownstreamFailure(
        String clientName,
        String methodKey,
        String httpMethod,
        String path,
        Integer httpStatus,
        DownstreamFailureKind kind,
        String downstreamCode,
        Throwable cause) {
}
