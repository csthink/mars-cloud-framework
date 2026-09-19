package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * @since 2025-10-30 09:01
 */
public class FailedException extends HttpException {

    public FailedException(ErrorCode errorCode) {
        super(HttpStatus.OK.value(), errorCode);
    }
}
