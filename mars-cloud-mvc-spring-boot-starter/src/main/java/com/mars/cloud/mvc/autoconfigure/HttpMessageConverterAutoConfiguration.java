package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.converter.JsonStringHttpMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 消息转换器顺序修正。
 *
 * <p><b>解决的问题</b>：控制器返回 {@code String} 时，Spring 默认链里
 * {@code StringHttpMessageConverter}（媒体类型为 text/plain 与通配符）排在
 * {@code JacksonJsonHttpMessageConverter} 之前，协商结果因此是 {@code text/plain}。
 * 统一响应 advice 已经把 body 换成 JSON 信封，但响应头仍是 {@code text/plain}——
 * 严格按 {@code Content-Type} 解析的客户端读不到 JSON。
 *
 * <p><b>为什么不能在 advice 里修</b>：{@code ResponseBodyAdvice} 在转换器**选定之后**才执行，
 * 此时改响应头已经改不动由谁序列化 body。
 *
 * <p><b>修法</b>：把一个只声明 JSON 媒体类型的字符串转换器放到链首
 * （见 {@link JsonStringHttpMessageConverter}）。它只在协商结果为 JSON 时生效，
 * 显式要求 {@code text/plain} 的接口（{@code produces} 或 {@code Accept}）走原转换器，不受影响。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
public class HttpMessageConverterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JsonStringHttpMessageConverter.class)
    public JsonStringHttpMessageConverter jsonStringHttpMessageConverter() {
        return new JsonStringHttpMessageConverter();
    }

    @Bean
    public WebMvcConfigurer jsonStringConverterOrderConfigurer(JsonStringHttpMessageConverter converter) {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                if (!converters.contains(converter)) {
                    // 放到链首：只有协商结果为 JSON 时它才会被选中（它只声明 JSON 媒体类型）
                    converters.add(0, converter);
                }
            }
        };
    }
}
