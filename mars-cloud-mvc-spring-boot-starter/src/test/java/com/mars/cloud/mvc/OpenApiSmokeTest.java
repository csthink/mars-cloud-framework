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
 * 接口文档（springdoc-openapi 3.0.3）的冒烟测试（T0.6）。
 *
 * <p>验证两件事：starter 引入依赖后 OpenAPI 端点真的可用；接口清单里确实包含业务接口。
 * 版本陷阱见 BOM 里的注释——**3.1.x 的 parent 是 Boot 4.1，不能升**。
 */
@SpringBootTest(classes = {App.class, TestController.class})
class OpenApiSmokeTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("/v3/api-docs 可访问，且是合法 OpenAPI 3 文档")
    void apiDocs_isServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info").exists())
                .andExpect(jsonPath("$.paths").exists());
    }

    @Test
    @DisplayName("接口清单包含业务接口（不是空文档）")
    void apiDocs_containsBusinessEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/ok']").exists())
                .andExpect(jsonPath("$.paths['/ok'].get").exists())
                .andExpect(jsonPath("$.paths['/business-error']").exists());
    }

    @Test
    @DisplayName("Swagger UI 页面可访问")
    void swaggerUi_isServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("YAML 形式的文档端点也可用")
    void apiDocsYaml_isServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk());
    }
}
