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
 * String 返回值与文案兜底的行为留痕（T0.4）。
 *
 * <p>本类记录当前真实行为：JSON 字符串原样透传；普通字符串被包成信封；
 * 文案兜底在 advice 层生效。其中**普通字符串的 Content-Type 偏差**是有意留痕的已知问题
 * （见 {@link #plainString_bodyIsEnvelopeButContentTypeIsTextPlain} 与 STATUS 的坏点表）。
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
    @DisplayName("【已知偏差】普通字符串：body 是正确信封，但 Content-Type 仍是 text/plain")
    void plainString_bodyIsEnvelopeButContentTypeIsTextPlain() throws Exception {
        // 已知偏差，不是回归：ResponseBodyAdvice 在转换器选定之后才执行，
        // 此时 StringHttpMessageConverter 已经抢到 String 返回值，改响应头也换不掉执行者。
        // 修复方案见 STATUS 的坏点表。
        MvcResult result = mockMvc.perform(get("/string-plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value("plain-text"))
                .andReturn();

        // 记下真实现状：body 正确，但响应头不是 application/json
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
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
    @DisplayName("根因留痕：StringHttpMessageConverter 排在 Jackson 转换器之前，先抢到 String 返回值")
    void converterOrderExplainsTheContentTypeDeviation() {
        List<String> converterNames = handlerAdapter.getMessageConverters().stream()
                .map(converter -> converter.getClass().getSimpleName())
                .collect(Collectors.toList());

        int stringIndex = indexOfFirst(converterNames, "StringHttpMessageConverter");
        int jacksonIndex = indexOfFirst(converterNames, "Jackson");

        assertThat(stringIndex).isGreaterThanOrEqualTo(0);
        assertThat(jacksonIndex).isGreaterThanOrEqualTo(0);
        // 这正是 Content-Type 偏差的机制：ResponseBodyAdvice 在转换器选定之后才执行，
        // 此时改响应头已经无法改变由谁序列化 body。
        assertThat(stringIndex).isLessThan(jacksonIndex);
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
