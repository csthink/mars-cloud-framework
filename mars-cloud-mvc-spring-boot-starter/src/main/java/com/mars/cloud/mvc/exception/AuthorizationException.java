package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * @since 2025-10-30 08:56
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AuthorizationException extends HttpException {
    public AuthorizationException(ErrorCode code) { super(HttpStatus.FORBIDDEN.value(), code); }
    public AuthorizationException(ErrorCode code, Object... args) { super(HttpStatus.FORBIDDEN.value(), code, args); }
}
