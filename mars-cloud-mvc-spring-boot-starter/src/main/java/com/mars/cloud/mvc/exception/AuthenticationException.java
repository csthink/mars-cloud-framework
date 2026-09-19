package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * @since 2025-10-30 08:42
 * 自定义认证异常，异常自带语义描述
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED) // 可选：作为文档/兜底；有全局切面时不强制需要
public class AuthenticationException extends HttpException {

    /**
     * 仅业务码，走 401 默认状态
     */
    public AuthenticationException(ErrorCode errorCode) {
        super(HttpStatus.UNAUTHORIZED.value(), errorCode);
    }

    /**
     * 业务码 + 占位符参数（用于 i18n 格式化）
     */
    public AuthenticationException(ErrorCode errorCode, Object... args) {
        super(HttpStatus.UNAUTHORIZED.value(), errorCode, args);
    }

    /**
     * 覆盖文案
     */
    public AuthenticationException(ErrorCode errorCode, String overrideMessage) {
        super(HttpStatus.UNAUTHORIZED.value(), errorCode, overrideMessage);
    }
}
