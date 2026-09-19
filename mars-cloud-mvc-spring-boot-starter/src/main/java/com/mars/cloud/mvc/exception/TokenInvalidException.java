package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * @since 2025-10-30 09:03
 */
public class TokenInvalidException extends HttpException {

    public TokenInvalidException(ErrorCode errorCode) {
        super(HttpStatus.UNAUTHORIZED.value(), errorCode);
    }
}
