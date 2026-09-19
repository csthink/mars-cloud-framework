package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.MvcTestApplication.TestController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mvc starter 的对外契约测试：统一响应信封、异常映射、错误码区间校验、i18n。
 *
 * <p>这些断言对应 T0.3 的验收条件——升级 Boot 4 / Jackson 3 之后，
 * 成功路径仍被包成信封、异常路径的错误码与文案保持不变。
 *
 * <p>说明：这里用 {@code MockMvcBuilders.webAppContextSetup} 手工装配而不是
 * {@code @AutoConfigureMockMvc}——Boot 4 把该注解移到了独立 artifact
 * {@code spring-boot-webmvc-test}，本测试不需要为此多引一个测试依赖。
 */
@SpringBootTest(classes = {App.class, TestController.class})
class MvcContractTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("成功路径：被包成信封，成功时不含 code / message")
    void success_isWrappedIntoEnvelope() throws Exception {
        mockMvc.perform(get("/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.name").value("hello"))
                .andExpect(jsonPath("$.result.value").value(1))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("返回 null：仍是成功信封，且无 result 字段")
    void nullBody_isStillSuccessEnvelope() throws Exception {
        mockMvc.perform(get("/empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("业务拒绝：HTTP 200 + success=false + 业务错误码 + i18n 文案")
    void businessException_keepsHttp200AndCarriesErrorCode() throws Exception {
        mockMvc.perform(get("/business-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("61901"))
                .andExpect(jsonPath("$.message").value("测试用资源不存在"));
    }

    @Test
    @DisplayName("缺必填参数：HTTP 400 + 错误码 400")
    void missingParameter_isBadRequest() throws Exception {
        mockMvc.perform(get("/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("路径参数类型不匹配：HTTP 400")
    void typeMismatch_isBadRequest() throws Exception {
        mockMvc.perform(get("/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("未预期异常：HTTP 500 + 兜底错误码 500")
    void unexpectedException_isInternalServerError() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("500"));
    }
}
