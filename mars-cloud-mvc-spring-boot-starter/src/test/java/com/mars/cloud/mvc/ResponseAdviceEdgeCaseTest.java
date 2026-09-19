package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.MvcTestApplication.TestController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一响应包装的边界行为契约（T0.4）。
 *
 * <p>与 {@link MvcContractTest} 的分工：那边管**主路径**（成功/异常映射），
 * 这里管**边界路径**——跳过包装、String 返回值、以及「调试详情只在开发环境回带」。
 * 这些是最容易在 Boot 4 / Jackson 3 升级中悄悄漂移的地方。
 */
class ResponseAdviceEdgeCaseTest {

    private MockMvc mockMvc;

    /**
     * 手工装配 MockMvc，避免依赖 Boot 4 已移出核心测试自动配置的 @AutoConfigureMockMvc。
     */
    abstract static class MockMvcSupport {

        @Autowired
        private WebApplicationContext webApplicationContext;

        MockMvc mockMvc;

        @BeforeEach
        void setUpMockMvc() {
            this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }
    }

    @Nested
    @SpringBootTest(classes = {App.class, TestController.class})
    class SkipWrapping extends MockMvcSupport {

        @Test
        @DisplayName("@IgnoreResponseAnnotation 在方法上：原样返回，不被包成信封")
        void ignoreAnnotationOnMethod_returnsBodyAsIs() throws Exception {
            mockMvc.perform(get("/ignored"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").doesNotExist())
                    .andExpect(jsonPath("$.result").doesNotExist())
                    .andExpect(jsonPath("$.raw").value("no-envelope"));
        }

        @Test
        @DisplayName("@IgnoreResponseAnnotation 在类上：该类所有方法都不被包装")
        void ignoreAnnotationOnClass_returnsBodyAsIs() throws Exception {
            mockMvc.perform(get("/ignored-class"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").doesNotExist())
                    .andExpect(jsonPath("$.raw").value("class-level"));
        }
    }

    @Nested
    @SpringBootTest(classes = {App.class, TestController.class})
    class NonDevEnvironment extends MockMvcSupport {

        @Test
        @DisplayName("非开发环境：失败响应不带任何调试详情（result 恒为 null）")
        void nonDevProfile_stripsDebugDetails() throws Exception {
            mockMvc.perform(get("/business-error"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("61901"))
                    .andExpect(jsonPath("$.result").doesNotExist());
        }
    }

    @Nested
    @SpringBootTest(classes = {App.class, TestController.class})
    @ActiveProfiles("dev")
    class DevEnvironment extends MockMvcSupport {

        @Test
        @DisplayName("开发环境：失败响应带上异常详情，便于排查")
        void devProfile_keepsDebugDetails() throws Exception {
            mockMvc.perform(get("/business-error"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("61901"))
                    .andExpect(jsonPath("$.result.detail").exists())
                    .andExpect(jsonPath("$.result.detail", containsString("61901")));
        }
    }
}
