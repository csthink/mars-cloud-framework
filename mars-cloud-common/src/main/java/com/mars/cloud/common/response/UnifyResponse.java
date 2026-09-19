package com.mars.cloud.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 统一响应信封。
 *
 * <p>刻意做成**纯 POJO**（只依赖 Jackson 注解与 Lombok，不依赖 Spring / Servlet）：
 * Servlet 栈由 {@code mars-cloud-mvc-spring-boot-starter} 的
 * {@code ResponseBodyAdvice} / {@code ControllerAdvice} 填充，
 * WebFlux 栈（网关）另行实现过滤器，两侧复用同一个信封，避免出现
 * 「网关返回一种错误格式、业务服务返回另一种」的分裂。
 *
 * <p>文案由调用方解析完成后传入，信封本身不做 i18n 查找 —— i18n 依赖
 * Spring 的 {@code MessageSource}，把它拉进本模块会破坏「common 无 Spring 依赖」的约束。
 *
 * @param <T> 业务数据类型
 */
@Getter
@Setter
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifyResponse<T> {

    /**
     * 业务成功或失败
     */
    private boolean success = true;

    /**
     * 错误码（成功时为空）
     */
    private String code;

    /**
     * 提示信息（成功时为空）
     */
    private String message;

    /**
     * 返回数据
     */
    private T result;

    /**
     * 成功，无数据
     */
    public static <T> UnifyResponse<T> success() {
        UnifyResponse<T> response = new UnifyResponse<>();
        response.setSuccess(true);
        return response;
    }

    /**
     * 成功，带数据
     */
    public static <T> UnifyResponse<T> success(T result) {
        UnifyResponse<T> response = new UnifyResponse<>();
        response.setSuccess(true);
        response.setResult(result);
        return response;
    }

    /**
     * 失败，带错误码与已解析的文案
     */
    public static <T> UnifyResponse<T> fail(int code, String message) {
        UnifyResponse<T> response = new UnifyResponse<>();
        response.setSuccess(false);
        response.setCode(String.valueOf(code));
        response.setMessage(message);
        return response;
    }

    /**
     * 失败，带错误码、已解析的文案与附加数据
     */
    public static <T> UnifyResponse<T> fail(int code, String message, T result) {
        UnifyResponse<T> response = new UnifyResponse<>();
        response.setSuccess(false);
        response.setCode(String.valueOf(code));
        response.setMessage(message);
        response.setResult(result);
        return response;
    }
}
