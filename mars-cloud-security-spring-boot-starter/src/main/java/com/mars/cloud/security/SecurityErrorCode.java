package com.mars.cloud.security;

import com.mars.cloud.common.error.ErrorCode;

/** Stable resource server and permission decision errors. */
public enum SecurityErrorCode implements ErrorCode {
    TOKEN_MISSING(62001, 401, "Bearer token required"),
    TOKEN_INVALID(62002, 401, "Invalid bearer token"),
    ACCESS_DENIED(62003, 403, "Access denied"),
    PDP_UNAVAILABLE(62004, 503, "Permission service unavailable"),
    PDP_PROTOCOL_ERROR(62005, 502, "Invalid permission service response"),
    CALLER_MISMATCH(62006, 403, "Caller does not match authenticated subject");

    private final int code;
    private final int status;
    private final String message;

    SecurityErrorCode(int code, int status, String message) {
        if (code < 62000 || code > 62999) throw new IllegalArgumentException("Invalid security error range");
        this.code = code;
        this.status = status;
        this.message = message;
    }
    public int getCode() { return code; }
    public int status() { return status; }
    public String defaultMessage() { return message; }
}
