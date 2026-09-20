package com.mars.cloud.mvc.autoconfigure;

import com.mars.cloud.mvc.converter.JsonStringHttpMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
 * <p><b>修法</b>：把一个只声明 JSON 媒体类型的字符串转换器放到默认转换器之前
 * （见 {@link JsonStringHttpMessageConverter}）。它只在协商结果为 JSON 时生效，
 * 显式要求 {@code text/plain} 的接口（{@code produces} 或 {@code Accept}）走原转换器，不受影响。
 *
 * <p><b>注册方式</b>：经 Boot 4 的 {@link ServerHttpMessageConvertersCustomizer} 调
 * {@code builder.addCustomConverter(...)}，该 API 的契约就是「位于默认转换器之前」，不再需要
 * {@code WebMvcConfigurer.extendMessageConverters}（Spring Framework 7 起标记删除）手工调顺序。
 * 转换器刻意<b>不</b>注册成 bean：Boot 只要看到容器里有 {@code StringHttpMessageConverter}
 * 类型的 bean，就不再注册自己那个 UTF-8 的字符串转换器，默认链里的 {@code text/plain}
 * 转换器会退回 ISO-8859-1，非拉丁字符乱码。
 *
 * <p><b>覆盖</b>：定义同名 bean {@code jsonStringHttpMessageConvertersCustomizer}
 * （类型 {@link ServerHttpMessageConvertersCustomizer}）即可替换本配置的注册逻辑。
 * 不能按类型条件化：Boot 自己也注册了同类型的 bean。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({WebMvcConfigurer.class, ServerHttpMessageConvertersCustomizer.class})
public class HttpMessageConverterAutoConfiguration {

    public static final String CUSTOMIZER_BEAN_NAME = "jsonStringHttpMessageConvertersCustomizer";

    @Bean(CUSTOMIZER_BEAN_NAME)
    @ConditionalOnMissingBean(name = CUSTOMIZER_BEAN_NAME)
    public ServerHttpMessageConvertersCustomizer jsonStringHttpMessageConvertersCustomizer() {
        JsonStringHttpMessageConverter converter = new JsonStringHttpMessageConverter();
        // addCustomConverter 的契约：自定义转换器位于默认转换器之前。
        // 它只声明 JSON 媒体类型，所以只有协商结果为 JSON 时才会被选中。
        return builder -> builder.addCustomConverter(converter);
    }
}
