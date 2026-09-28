package com.mars.cloud.mvc.error;

import com.mars.cloud.common.error.ErrorCode;

/** Error codes owned by the MVC starter. */
public enum MvcErrorCode implements ErrorCode {

    RATE_LIMITED(61006);

    private final int code;

    MvcErrorCode(int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
