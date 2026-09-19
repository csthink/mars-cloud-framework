package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * @since 2025-10-30 08:57
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends HttpException {

    public ConflictException(ErrorCode code) {
        super(HttpStatus.CONFLICT.value(), code);
    }
}
