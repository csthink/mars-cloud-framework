package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * @since 2025-10-30 08:56
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends HttpException {

    public ResourceNotFoundException(ErrorCode code) {
        super(HttpStatus.NOT_FOUND.value(), code);
    }
}
