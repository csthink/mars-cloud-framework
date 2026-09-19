package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.MvcTestApplication.TestController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * String 返回值与文案兜底的行为契约。
 *
 * <p>覆盖三条路径：普通字符串被包成信封、JSON 字符串原样透传、文案兜底在 advice 层生效。
 * 三者的 {@code Content-Type} 都必须是 {@code application/json}——这正是历史偏差
 * （body 是 JSON 信封但响应头是 {@code text/plain}）修复后的断言点。
 */
@SpringBootTest(classes = {App.class, TestController.class})
class StringReturnAndFallbackTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("i18n 未命中 → 响应文案由 advice 的兜底配置给出")
    void fallbackMessage_isResolvedByAdvice() throws Exception {
        mockMvc.perform(get("/fallback-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("61990"))
                .andExpect(jsonPath("$.message").value("兜底配置里的文案"));
    }

    @Test
    @DisplayName("普通字符串：包成信封，Content-Type 为 application/json")
    void plainString_isWrappedIntoEnvelopeWithJsonContentType() throws Exception {
        MvcResult result = mockMvc.perform(get("/string-plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value("plain-text"))
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("显式要求 text/plain 的接口不受影响：仍返回 text/plain 与原始字符串")
    void explicitTextPlain_endpointIsNotHijacked() throws Exception {
        MvcResult result = mockMvc.perform(get("/string-text-plain").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("plain-text");
    }

    @Test
    @DisplayName("JSON 字符串：原样透传，且 Content-Type 为 application/json")
    void jsonString_isPassedThroughUnchanged() throws Exception {
        MvcResult result = mockMvc.perform(get("/string-json"))
                .andExpect(status().isOk())
                .andReturn();

        // T0.3 之前这里会把已合法的 JSON 再序列化一次，得到 "{\"k\":\"v\"}" 这种双重转义
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"name\":\"直接透传\"}");
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("修正机制留痕：只声明 JSON 的字符串转换器被插到了链首")
    void jsonOnlyStringConverterIsFirst() {
        List<String> converterNames = handlerAdapter.getMessageConverters().stream()
                .map(converter -> converter.getClass().getSimpleName())
                .collect(Collectors.toList());

        // 它在链首，且只声明 JSON 媒体类型——协商出 text/plain 时不会参与，
        // 所以显式要求 text/plain 的接口仍由 StringHttpMessageConverter 处理
        assertThat(converterNames.get(0)).isEqualTo("JsonStringHttpMessageConverter");
    }

    private static int indexOfFirst(List<String> names, String fragment) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("转换器链里确实存在可写 JSON 的转换器（Jackson 3）")
    void jacksonConverterIsPresent() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();

        assertThat(converters).anyMatch(c -> c.getClass().getSimpleName().contains("Jackson"));
    }
}
