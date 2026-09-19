package com.mars.cloud.mvc.advice;


import com.baomidou.lock.exception.LockFailureException;
import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.mvc.error.ExceptionCodeConfiguration;
import com.mars.cloud.mvc.exception.AuthenticationException;
import com.mars.cloud.mvc.exception.HttpException;
import com.mars.cloud.mvc.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @since 2025-10-29 18:00
 * 全局异常捕获
 * - i18n key 统一：error.code.<数字>
 * - 先 i18n，后 ExceptionCodeConfiguration 兜底，再后默认文案
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.mars.cloud")
@RequiredArgsConstructor
public class GlobalExceptionAdvice {

    private final AppContextHolder appContextHolder;
    private final ExceptionCodeConfiguration codeConfiguration;

    /* ----------------------------- 业务异常（你自定义的） ----------------------------- */

    @ExceptionHandler(HttpException.class)
    public ResponseEntity<UnifyResponse<Object>> handleHttpException(HttpException ex) {
        log.error("HttpException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);

        int code = ex.getErrCode(); // 业务码
        String message = (ex.getErrorEnum() != null)
                ? msg(ex.getErrorEnum(), ex.getMessage())
                : msg(code, ex.getMessage());

        // ✅ 这里用 ErrorCode，而不是 int
        ErrorCode ec = ex.getErrorEnum() != null ? ex.getErrorEnum() : of(code);
        UnifyResponse<Object> body = failBody(ec, message);
        buildErrorResult(body, ex.getMessage());

        HttpStatus status = HttpStatus.resolve(ex.getHttpStatusCode());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        return ResponseEntity.status(status).body(body);
    }

    /* 鉴权异常 → 401（模式A：响应体 code=业务码，HTTP=401） */
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(AuthenticationException.class)
    public UnifyResponse<Object> handleAuthenticationException(AuthenticationException ex) {
        log.error("AuthenticationException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);

        // 优先取业务码/枚举，兜底直接用异常里的 errCode
        ErrorCode ec = ex.getErrorEnum() != null ? ex.getErrorEnum() : of(ex.getErrCode());
        String message = msg(ec.getCode(), ex.getMessage());

        UnifyResponse<Object> body = failBody(ec, message);
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    /* ----------------------------- 常见 Web 层异常 → 4xx ----------------------------- */

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public UnifyResponse<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        log.error("MethodArgumentNotValidException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);

        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> Optional.ofNullable(fe.getDefaultMessage()).orElse("invalid"),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        String joinMsg = ex.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        if (joinMsg.isEmpty()) {
            joinMsg = "Bad Request";
        }

        // ✅ of(400)
        UnifyResponse<Object> body = failBody(of(400), msg(400, joinMsg));
        buildErrorResult(body, fieldErrors);
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BindException.class)
    public UnifyResponse<Object> handleBindException(BindException ex) {
        log.error("BindException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        String joinMsg = ex.getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        if (joinMsg.isEmpty()) joinMsg = "Bad Request";

        UnifyResponse<Object> body = failBody(of(400), msg(400, joinMsg));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public UnifyResponse<Object> handleMissingParam(MissingServletRequestParameterException ex) {
        log.error("MissingServletRequestParameterException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        String detail = String.format("缺少必要参数: name=%s, type=%s", ex.getParameterName(), ex.getParameterType());
        UnifyResponse<Object> body = failBody(of(400), msg(400, detail));
        buildErrorResult(body, detail);
        return body;
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public UnifyResponse<Object> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.error("HttpRequestMethodNotSupportedException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(405), msg(405, "Method Not Allowed"));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public UnifyResponse<Object> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.error("HttpMediaTypeNotAcceptableException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(406), msg(406, "Not Acceptable"));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public UnifyResponse<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.error("MethodArgumentTypeMismatchException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        String detail = String.format("参数类型不匹配: name=%s, value=%s", ex.getName(), ex.getValue());
        UnifyResponse<Object> body = failBody(of(400), msg(400, detail));
        buildErrorResult(body, detail);
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public UnifyResponse<Object> handleMethodValidation(HandlerMethodValidationException ex) {
        log.error("HandlerMethodValidationException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);

        String joinMsg = ex.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream())
                .map(e -> e.getDefaultMessage())
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        if (joinMsg.isEmpty()) joinMsg = "Bad Request";

        UnifyResponse<Object> body = failBody(of(400), msg(400, joinMsg));
        buildErrorResult(body, joinMsg);
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ConstraintViolationException.class)
    public UnifyResponse<Object> handleConstraintViolation(ConstraintViolationException ex) {
        log.error("ConstraintViolationException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);

        Map<String, String> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> violations.put(v.getPropertyPath().toString(), v.getMessage()));

        String joinMsg = violations.values().stream().collect(Collectors.joining("; "));
        if (joinMsg.isEmpty()) joinMsg = "Bad Request";

        UnifyResponse<Object> body = failBody(of(400), msg(400, joinMsg));
        buildErrorResult(body, violations);
        return body;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoHandlerFoundException.class)
    public UnifyResponse<Object> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.error("NoHandlerFoundException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(404), msg(404, "Not Found"));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public UnifyResponse<Object> handleIllegalArgument(IllegalArgumentException ex) {
        log.error("IllegalArgumentException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(400), msg(400, ex.getMessage()));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(LockFailureException.class)
    public UnifyResponse<Object> handleLockFailure(LockFailureException ex) {
        log.error("LockFailureException, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(409), msg(409, "Conflict"));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    /* ----------------------------- 兜底 5xx ----------------------------- */

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public UnifyResponse<Object> handleOther(Exception ex) {
        log.error("Unhandled Exception, URL=[{}]", RequestUtil.getRequestMethodAndUri(), ex);
        UnifyResponse<Object> body = failBody(of(500), msg(500, ex.getMessage()));
        buildErrorResult(body, ex.getMessage());
        return body;
    }

    /* ----------------------------- 私有工具方法 ----------------------------- */

    /**
     * i18n 主通道：error.code.<数字>
     * 1) I18nUtil.getMessage("error.code."+code, null) 命中则用
     * 2) 再尝试纯数字 key（兼容一些历史 bundle）
     * 3) 再回退 ExceptionCodeConfiguration（config/exception-code.properties）
     * 4) 最后用 defaultMsg 或 code 字符串
     */
    private String msg(int code, String defaultMsg) {
        String key = "error.code." + code;

        // 1) 规范 key：error.code.<数字>（返回 null 表示未命中）
        String m = I18nUtil.getMessage(key, (String) null);
        if (m != null && !m.isEmpty()) {
            return m;
        }

        // 2) 兼容纯数字 key
        String numeric = I18nUtil.getMessage(String.valueOf(code), (String) null);
        if (numeric != null && !numeric.isEmpty()) {
            return numeric;
        }

        // 3) 本服务兜底配置（config/exception-code.properties 的 mars.codes[<code>]=...）
        String fallback = codeConfiguration.getMessage(code, null);
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }

        // 4) 最终兜底
        return (defaultMsg == null || defaultMsg.isEmpty()) ? java.lang.String.valueOf(code) : defaultMsg;
    }

    private String msg(ErrorCode errorCode, String defaultMsg) {
        return msg(errorCode.getCode(), defaultMsg);
    }


    private void buildErrorResult(UnifyResponse<Object> body, Object debugDetails) {
        if (!appContextHolder.isDevEnv()) {
            body.setResult(null);
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detail", debugDetails);

        HttpServletRequest request = HttpContextUtil.getRequest();
        if (request != null) {
            details.put("ip", IpUtil.getIPAddress(request));
            details.put("method", request.getMethod());
            details.put("uri", request.getRequestURI());
            Map<String, String> headers = new LinkedHashMap<>();
            Enumeration<String> names = request.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String n = names.nextElement();
                headers.put(n, request.getHeader(n));
            }
            details.put("headers", headers);
        }

        body.setResult(details);
    }

    /* ----------------------------- 信封构造 ----------------------------- */

    /**
     * 构造失败信封：错误码与文案在 advice 侧解析完成后交给 common 的信封，
     * 信封本身不依赖 Spring，可被 WebFlux 侧复用。
     */
    private static UnifyResponse<Object> failBody(ErrorCode errorCode, String message) {
        return UnifyResponse.fail(errorCode.getCode(), message);
    }

    private static UnifyResponse<Object> failBody(int code, String message) {
        return UnifyResponse.fail(code, message);
    }

    /**
     * 把纯 int 适配成 ErrorCode（函数式接口）
     */
    private static ErrorCode of(int code) {
        return () -> code;
    }
}
