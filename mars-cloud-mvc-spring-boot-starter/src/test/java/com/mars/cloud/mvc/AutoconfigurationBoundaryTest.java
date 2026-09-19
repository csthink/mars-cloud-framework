package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.BoundaryRegistrarConfig;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.DuplicateRegistrarConfig;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.OutOfRangeRegistrarConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码区间校验与 Web 栈条件装配的边界契约（T0.4）。
 *
 * <p>区间校验的价值在于把错误码冲突从运行期提前到启动期，所以这里**故意制造违规**，
 * 断言应用启动失败——而不是只测正常路径。
 */
class AutoconfigurationBoundaryTest {

    /**
     * ApplicationContextRunner 不会加载 src/test/resources/application.yml
     * （那是 @SpringBootTest 的机制），所以区间属性必须显式给，
     * 否则范围会退化成 [0,0]，连合法码都会越界。
     */
    private static final String[] RANGE_PROPERTIES = {
            "mars.error-code.validate=true",
            "mars.error-code.range.service=mvc-contract-test",
            "mars.error-code.range.start=61900",
            "mars.error-code.range.end=61999",
    };

    @Nested
    @DisplayName("启动期错误码区间校验")
    class RangeValidation {

        @Test
        @DisplayName("错误码越界 → 启动失败")
        void codeOutOfRange_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues(RANGE_PROPERTIES)
                    .withUserConfiguration(App.class, OutOfRangeRegistrarConfig.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("错误码 70001 不在服务");
                    });
        }

        @Test
        @DisplayName("错误码重复 → 启动失败")
        void duplicateCode_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues(RANGE_PROPERTIES)
                    .withUserConfiguration(App.class, DuplicateRegistrarConfig.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("内部错误码重复");
                    });
        }

        @Test
        @DisplayName("恰好落在区间边界 → 启动成功")
        void boundaryCodes_areAccepted() {
            new ApplicationContextRunner()
                    .withPropertyValues(RANGE_PROPERTIES)
                    .withUserConfiguration(App.class, BoundaryRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("区间校验关闭（mars.error-code.validate=false）→ 越界不再拦截")
        void validationDisabled_skipsCheck() {
            new ApplicationContextRunner()
                    .withPropertyValues(RANGE_PROPERTIES[0].replace("=true", "=false"),
                            RANGE_PROPERTIES[1], RANGE_PROPERTIES[2], RANGE_PROPERTIES[3])
                    .withUserConfiguration(App.class, OutOfRangeRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("Web 栈条件装配")
    class WebStackConditions {

        @Test
        @DisplayName("响应式栈（网关形态）：Servlet 专属的自动配置不装配")
        void reactiveStack_doesNotLoadServletAdvice() {
            new ReactiveWebApplicationContextRunner()
                    .withPropertyValues(RANGE_PROPERTIES)
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean("globalResponseAdvice");
                        assertThat(context).doesNotHaveBean("globalExceptionAdvice");
                        assertThat(context).doesNotHaveBean("httpContextUtilFilterRegistration");
                    });
        }
    }
}
