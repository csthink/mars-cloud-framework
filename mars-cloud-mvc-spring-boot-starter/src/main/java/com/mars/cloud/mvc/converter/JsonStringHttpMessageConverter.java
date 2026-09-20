package com.mars.cloud.mvc.converter;

import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 只声明 JSON 媒体类型的字符串转换器。
 *
 * <p><b>为什么需要它：</b>控制器的返回值是 {@code String} 时，Spring 的
 * {@code StringHttpMessageConverter} 会先被选中，而它注册的媒体类型是 text/plain 加通配符，
 * 于是协商出的响应类型是 {@code text/plain}——
 * 即使统一响应 advice 已经把 body 换成了 JSON 信封，响应头仍然是 {@code text/plain}。
 * 把 advice 插到转换器之前是不可行的：{@code ResponseBodyAdvice} 在转换器**选定之后**才执行，
 * 改响应头也换不掉执行者。
 *
 * <p><b>做法：</b>本转换器继承 {@code StringHttpMessageConverter}（行为完全一致），
 * 只是把媒体类型收窄为 {@code application/json}。这样它只在协商结果是 JSON 时才参与，
 * 不会抢走真正需要 {@code text/plain} 的响应（例如 {@code produces = "text/plain"}）。
 *
 * <p><b>注册方式见 {@code HttpMessageConverterAutoConfiguration}：</b>它经 Boot 的
 * {@code ServerHttpMessageConvertersCustomizer} 用 {@code addCustomConverter} 加入，
 * 按该 API 的契约位于所有默认转换器之前。它<b>不是</b>容器里的 bean：
 * 一旦以 {@code StringHttpMessageConverter} 子类型的 bean 存在，Boot 会认为使用者已自定义字符串转换器，
 * 不再注册自己那个 UTF-8 的 {@code StringHttpMessageConverter}，默认链里的 {@code text/plain}
 * 转换器就退回 ISO-8859-1，非拉丁字符乱码。
 *
 * @since 2026-09-19
 */
public class JsonStringHttpMessageConverter extends StringHttpMessageConverter {

    /**
     * 默认 UTF-8。JSON 写出时本来就按 {@code application/json} 解析成 UTF-8，
     * 这里只是不让类落到父类的 ISO-8859-1 默认值。
     */
    public JsonStringHttpMessageConverter() {
        this(StandardCharsets.UTF_8);
    }

    public JsonStringHttpMessageConverter(Charset defaultCharset) {
        super(defaultCharset);
        setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
    }
}
