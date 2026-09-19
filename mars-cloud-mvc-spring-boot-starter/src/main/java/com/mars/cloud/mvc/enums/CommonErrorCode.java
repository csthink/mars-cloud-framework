package com.mars.cloud.mvc.enums;

import com.mars.cloud.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用错误码常量。
 *
 * <p><b>不要把这些码注册给 {@code ErrorCodeRegistrar}。</b>它们的取值
 * （{@code -1} / {@code 100} / {@code 400} / {@code 1000}…）都落在本框架的
 * 区间分配表之外，注册后会在启动期被判为「不在已声明的任何区间内」而**拒绝启动**。
 *
 * <p>框架自身兜底用的状态码（参数校验失败的 400、未预期异常的 500 等）由全局异常处理
 * 直接产生，**不经过区间校验**，因此也不需要在这里声明。业务服务的错误码请在自己的
 * 声明区段内定义，见 {@code docs/error-code.md}。
 *
 * @since 2025-10-29 13:50
 */
@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    UNKNOWN_ERROR(-1),
    INVALID_PARAMETER(100),

    /**
     * 4xx
     */
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    NOT_ACCEPTABLE(406),

    /**
     * 5xx
     */
    INTERNAL_SERVER_ERROR(500),

    GENERAL_ERROR(1000),
    GENERAL_PARAM_ERROR(1001),

    ;

    private final int code;
}
