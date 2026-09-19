package com.mars.cloud.mvc.advice;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mars.cloud.common.domain.util.JsonUtil;
import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 统一响应包装。
 *
 * <p>把控制器返回值包成 {@link UnifyResponse}。以下情况跳过包装：
 * <ul>
 *   <li>类或方法标注了 {@link IgnoreResponseAnnotation}</li>
 *   <li>返回值已经是 {@code UnifyResponse}</li>
 * </ul>
 *
 * @since 2025-10-29 10:33
 */
@Slf4j
@RestControllerAdvice(basePackages = {"com.mars.cloud"})
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 仅用于把 String 返回值序列化成 JSON、以及校验字符串是不是合法 JSON。
     * 不参与业务对象的序列化——那是容器里 HttpMessageConverter 的 ObjectMapper 的职责。
     *
     * <p>Jackson 3 的 {@code ObjectMapper} 不可变配置，故用 builder 构建。
     */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

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
            if (isValidJson(body.toString())) {
                // 本身已是合法 JSON：原样透传。**不要再序列化一次**，否则字符串会被转义成
                // "{\"k\":\"v\"}" 这种双重编码的形态
                response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                return body.toString();
            } else {
                // 非 JSON 的字符串必须序列化成 JSON 再返回，否则会与 StringHttpMessageConverter 冲突
                return JsonUtil.toJson(UnifyResponse.success(body));
            }
        }

        return UnifyResponse.success(body);
    }

    private static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(json);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

}
