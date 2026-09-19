package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * @since 2025-10-30 08:57
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends HttpException {

    public BadRequestException(ErrorCode code, Object... args) {
        super(HttpStatus.BAD_REQUEST.value(), code, args);
    }
}
