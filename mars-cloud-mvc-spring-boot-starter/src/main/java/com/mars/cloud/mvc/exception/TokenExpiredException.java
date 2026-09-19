package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * @since 2025-10-30 09:02
 */
public class TokenExpiredException extends HttpException {

    public TokenExpiredException(ErrorCode errorCode) {
        super(HttpStatus.UNAUTHORIZED.value(), errorCode);
    }
}
