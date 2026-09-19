package com.mars.cloud.mvc.advice;

import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.common.domain.util.Jackson2Util;
import com.mars.cloud.common.response.UnifyResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Objects;

/**
 * @since 2025-10-29 10:33
 */
@Slf4j
@RestControllerAdvice(basePackages = {"com.mars.cloud"})
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断 beforeBodyWrite 方法是否会执行,true: 执行, false: 放行
     * 不需要进行增强操作可以在 supports 方法里进行判断
     *
     * @param returnType    MethodParameter
     * @param converterType HttpMessageConverter
     * @return boolean
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> targetClass = Objects.requireNonNull(returnType.getMethod()).getDeclaringClass();
        log.debug("supports execute methodParameter=[{}] targetClass=[{}] class=[{}]",
                returnType, targetClass, converterType);

        // 如果类上声明了 IgnoreResponseAnnotation 注解，则不做处理
        if (returnType.getDeclaringClass().isAnnotationPresent(IgnoreResponseAnnotation.class)) {
            return false;
        }

        // 如果方法上声明了 IgnoreResponseAnnotation 注解，则不做处理
        if (returnType.getMethod().isAnnotationPresent(IgnoreResponseAnnotation.class)) {
            return false;
        }

        // 如果接口返回的类型是 UnifyResponse 则不做任何额外的处理
        if (returnType.getParameterType().equals(UnifyResponse.class)) {
            return false;
        }

        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        log.debug("beforeBodyWrite data=[{}]", body);

        if (null == body) {
            return UnifyResponse.success();
        } else if (body instanceof UnifyResponse) {
            // 如果已经包装为统一响应体则直接返回, 也可以在 supports 方法中做判断
            return body;
        } else if (body instanceof String || String.class.equals(returnType.getGenericParameterType())) {
            // 若原返回结果为 String，则转换为 JSON 响应体再返回
            if (isValidJson(body.toString())){
                // 只有本身是 json 的直接以 json 形式返回
                response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                return Jackson2Util.toJson((body));
            } else {
                try {
                    return objectMapper.writeValueAsString(UnifyResponse.success(body));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("包装 String 类型响应失败", e);
                }
            }
        }

        return UnifyResponse.success(body);
    }

    public static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
