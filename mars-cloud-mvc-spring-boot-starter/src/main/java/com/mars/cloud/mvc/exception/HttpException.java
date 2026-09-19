package com.mars.cloud.mvc.exception;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.util.I18nUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * @since 2025-10-30 08:23
 */
@Getter
@Slf4j
public class HttpException extends RuntimeException {

    /**
     * 统一协议错误码（可为 null，兼容纯数值码构造）
     */
    private final ErrorCode errorCode;

    /**
     * 纯数字错误码（全局唯一）
     */
    private final int errCode;

    /**
     * 已解析的错误消息（优先 i18n）
     */
    private final String errorMsg;

    /**
     * 可选的 HTTP 状态码（为 null 时由切面默认 500）
     */
    private final Integer httpStatusCode;


    public HttpException(ErrorCode code) {
        this(null, code, null, null, null);
    }

    /**
     * 占位符参数 -> i18n 格式化
     */
    public HttpException(ErrorCode code, Object... args) {
        this(null, code, null, args, null);
    }

    /**
     * 指定覆盖文案（不走 i18n）
     */
    public HttpException(ErrorCode code, String overrideMessage) {
        this(null, code, overrideMessage, null, null);
    }

    public HttpException(int httpStatusCode, ErrorCode code) {
        this(httpStatusCode, code, null, null, null);
    }

    public HttpException(int httpStatusCode, ErrorCode code, Object... args) {
        this(httpStatusCode, code, null, args, null);
    }

    public HttpException(int httpStatusCode, ErrorCode code, String overrideMessage) {
        this(httpStatusCode, code, overrideMessage, null, null);
    }

    /**
     * 兼容迁移：仅给定数值码和消息（少用，过渡期可用）
     */
    public HttpException(int httpStatusCode, int errCode, String errorMsg) {
        super(buildSuperMessage(errCode, errorMsg));
        this.errorCode = null;
        this.errCode = errCode;
        this.errorMsg = (errorMsg == null || errorMsg.isEmpty()) ? String.valueOf(errCode) : errorMsg;
        this.httpStatusCode = httpStatusCode;
    }


    /* ======================== 工厂方法（可选） ======================== */

    public static HttpException badRequest(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.BAD_REQUEST.value(), code, args);
    }

    public static HttpException unauthorized(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.UNAUTHORIZED.value(), code, args);
    }

    public static HttpException forbidden(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.FORBIDDEN.value(), code, args);
    }

    public static HttpException notFound(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.NOT_FOUND.value(), code, args);
    }

    public static HttpException conflict(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.CONFLICT.value(), code, args);
    }

    public static HttpException internalServerError(ErrorCode code, Object... args) {
        return new HttpException(HttpStatus.INTERNAL_SERVER_ERROR.value(), code, args);
    }

    /* ======================== 兼容切面的取值方法 ======================== */

    /**
     * 兼容你切面里的 ex.getErrorEnum() 写法
     */
    public ErrorCode getErrorEnum() {
        return this.errorCode;
    }

    public int getErrCode() {
        return this.errCode;
    }

    public Integer getHttpStatusCode() {
        return this.httpStatusCode;
    }

    /* ======================== 统一到核心构造 ======================== */

    public HttpException(Integer httpStatusCode, ErrorCode code, String overrideMessage, Object[] args, Throwable cause) {
        super(buildSuperMessage(code != null ? code.getCode() : null, overrideMessage), cause);
        this.errorCode = code;
        this.errCode = (code != null) ? code.getCode() : -1;
        this.errorMsg = resolveMessage(code, overrideMessage, args);
        this.httpStatusCode = httpStatusCode;
    }

    /* ======================== 私有工具方法 ======================== */

    private static String resolveMessage(ErrorCode code, String overrideMessage, Object[] args) {
        if (overrideMessage != null && !overrideMessage.isEmpty()) {
            return overrideMessage;
        }
        if (code == null) {
            return "HttpException";
        }
        // 1) 规范 key：error.code.<数字>
        String key = "error.code." + code.getCode();
        String msg = I18nUtil.getMessage(key, args);
        if (msg != null && !msg.isEmpty()) {
            return msg;
        }
        // 2) 兼容纯数字 key
        msg = I18nUtil.getMessage(String.valueOf(code.getCode()), args);
        if (msg != null && !msg.isEmpty()) {
            return msg;
        }
        // 3) 兜底：数字码
        return String.valueOf(code.getCode());
    }

    private static String buildSuperMessage(Integer code, String message) {
        if (code == null) {
            return message == null ? "HttpException" : message;
        }
        return (message == null || message.isEmpty()) ? String.valueOf(code) : (code + " - " + message);
    }
}
