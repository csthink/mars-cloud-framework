package com.mars.cloud.feign;

/**
 * 下游调用失败的稳定分类。
 */
public enum DownstreamFailureKind {

    /** HTTP 2xx，但统一信封明确表示业务失败。 */
    BUSINESS_ENVELOPE,

    /** 下游返回非 2xx HTTP 状态。 */
    HTTP,

    /** 注册中心无实例或连接在重试耗尽后仍失败。 */
    UNAVAILABLE,

    /** 连接或读取在重试耗尽后超时。 */
    TIMEOUT,

    /** 响应为空、不是统一信封或 JSON 无法解析。 */
    MALFORMED_RESPONSE
}
