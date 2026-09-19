package com.mars.cloud.mvc.enums;

import com.mars.cloud.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
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
