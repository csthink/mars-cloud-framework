package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.BoundaryRegistrarConfig;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.DuplicateRegistrarConfig;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.OutOfRangeRegistrarConfig;
import com.mars.cloud.mvc.fixture.RangeViolationFixtures.SecurityLayerRegistrarConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码区间校验与 Web 栈条件装配的边界契约。
 *
 * <p>区间校验的价值在于把配置事故从运行期提前到启动期，所以这里**故意制造违规**，
 * 断言应用启动失败——而不是只测正常路径。
 *
 * <p>{@code ApplicationContextRunner} 不会加载 {@code application.yml}（那是
 * {@code @SpringBootTest} 的机制），所以区间属性必须显式给。
 */
class AutoconfigurationBoundaryTest {

    /**
     * 框架层只启用 common 与 mvc；业务服务声明 66000 段的前半段。
     *
     * <p>mvc 层是必需的：测试宿主 {@code App} 自己注册了 61000 段的错误码。
     */
    private static final String[] BASE_PROPERTIES = {
            "mars.error-code.validate=true",
            "mars.error-code.framework-layers[0]=common",
            "mars.error-code.framework-layers[1]=mvc",
            "mars.error-code.ranges[0].owner=business",
            "mars.error-code.ranges[0].start=66000",
            "mars.error-code.ranges[0].end=66050",
    };

    /** 同上，但不启用任何框架层——用于测「归属层未启用」与「同名多段」这两类配置。 */
    private static final String[] BASE_WITHOUT_FRAMEWORK_LAYERS = {
            "mars.error-code.validate=true",
            "mars.error-code.ranges[0].owner=business",
            "mars.error-code.ranges[0].start=66000",
            "mars.error-code.ranges[0].end=66050",
    };

    @Nested
    @DisplayName("启动期错误码区间校验")
    class RangeValidation {

        @Test
        @DisplayName("错误码越界（66099 不在声明的 66000–66050 内）→ 启动失败")
        void codeOutOfRange_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withUserConfiguration(App.class, OutOfRangeRegistrarConfig.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("错误码 66099 不在 [business] 已声明的任何区间内");
                    });
        }

        @Test
        @DisplayName("错误码重复 → 启动失败")
        void duplicateCode_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withUserConfiguration(App.class, DuplicateRegistrarConfig.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("错误码重复注册: 66002");
                    });
        }

        @Test
        @DisplayName("恰好落在区间边界 → 启动成功")
        void boundaryCodes_areAccepted() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withUserConfiguration(App.class, BoundaryRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("区间校验关闭（mars.error-code.validate=false）→ 越界不再拦截")
        void validationDisabled_skipsCheck() {
            String[] disabled = BASE_PROPERTIES.clone();
            disabled[0] = "mars.error-code.validate=false";
            new ApplicationContextRunner()
                    .withPropertyValues(disabled)
                    .withUserConfiguration(App.class, OutOfRangeRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("归属校验")
    class OwnershipValidation {

        @Test
        @DisplayName("错误码归属的层未启用 → 启动失败并指出该声明哪一层")
        void unclaimedLayer_failsFast() {
            // 故意不启用 mvc 层，而测试宿主自身注册的 61901 属于 mvc 段
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_WITHOUT_FRAMEWORK_LAYERS)
                    .withUserConfiguration(App.class, SecurityLayerRegistrarConfig.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("属于 [security] 区段，但该区段未启用");
                    });
        }

        @Test
        @DisplayName("把 security 段声明进 ranges 后，62000 段的错误码被接受")
        void enablingSecurityRange_acceptsItsCodes() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withPropertyValues("mars.error-code.ranges[1].owner=security",
                            "mars.error-code.ranges[1].start=62000",
                            "mars.error-code.ranges[1].end=62999")
                    .withUserConfiguration(App.class, SecurityLayerRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("业务服务可把区间收窄到分配区段的一部分")
        void businessRangeCanNarrowWithinAllocation() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withUserConfiguration(App.class, BoundaryRegistrarConfig.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Nested
    @DisplayName("配置自检")
    class ConfigurationValidation {

        @Test
        @DisplayName("归属名不存在 → 启动失败并列出可选值")
        void unknownOwner_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues("mars.error-code.ranges[0].owner=not-a-layer",
                            "mars.error-code.ranges[0].start=66000",
                            "mars.error-code.ranges[0].end=66099")
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("归属名 [not-a-layer] 不存在");
                    });
        }

        @Test
        @DisplayName("声明的区间超出该归属的分配区段 → 启动失败")
        void rangeBeyondAllocation_failsFast() {
            // business 的分配区段到 99999 为止，声明到 100000 就越界
            new ApplicationContextRunner()
                    .withPropertyValues("mars.error-code.ranges[0].owner=business",
                            "mars.error-code.ranges[0].start=66000",
                            "mars.error-code.ranges[0].end=100000")
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("超出该归属的分配区段");
                    });
        }

        @Test
        @DisplayName("同一区段内两个业务服务的区间重叠 → 启动失败")
        void overlappingBusinessRanges_failFast() {
            // 这正是「多个业务服务共用 business 区段」时会踩的坑：
            // 服务 A 占 66000–66499，服务 B 占 66400–66899，两者在 66400–66499 上撞车
            new ApplicationContextRunner()
                    .withPropertyValues("mars.error-code.validate=true",
                            "mars.error-code.framework-layers[0]=common",
                            "mars.error-code.ranges[0].owner=business",
                            "mars.error-code.ranges[0].start=66000",
                            "mars.error-code.ranges[0].end=66499",
                            "mars.error-code.ranges[1].owner=business",
                            "mars.error-code.ranges[1].start=66400",
                            "mars.error-code.ranges[1].end=66899")
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("错误码区间重叠");
                    });
        }

        @Test
        @DisplayName("同一区段内不重叠的两个业务服务 → 启动成功")
        void nonOverlappingBusinessRanges_areAccepted() {
            new ApplicationContextRunner()
                    .withPropertyValues("mars.error-code.validate=true",
                            "mars.error-code.framework-layers[0]=mvc",
                            "mars.error-code.ranges[0].owner=business",
                            "mars.error-code.ranges[0].start=66000",
                            "mars.error-code.ranges[0].end=66499",
                            "mars.error-code.ranges[1].owner=business",
                            "mars.error-code.ranges[1].start=66500",
                            "mars.error-code.ranges[1].end=66999")
                    .withUserConfiguration(App.class)
                    .run(context -> assertThat(context).hasNotFailed());
        }

        @Test
        @DisplayName("框架层名写错 → 启动失败")
        void unknownFrameworkLayer_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues("mars.error-code.framework-layers[0]=nope")
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("不是框架层");
                    });
        }

        @Test
        @DisplayName("同一归属被声明两次（framework-layers 与 ranges 各一次）→ 启动失败")
        void duplicateDeclaration_failsFast() {
            new ApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
                    .withPropertyValues("mars.error-code.ranges[1].owner=mvc",
                            "mars.error-code.ranges[1].start=61000",
                            "mars.error-code.ranges[1].end=61999")
                    .withUserConfiguration(App.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("错误码区间重叠: mvc");
                    });
        }
    }

    @Nested
    @DisplayName("Web 栈条件装配")
    class WebStackConditions {

        @Test
        @DisplayName("响应式栈（网关形态）：Servlet 专属的自动配置不装配")
        void reactiveStack_doesNotLoadServletAdvice() {
            new ReactiveWebApplicationContextRunner()
                    .withPropertyValues(BASE_PROPERTIES)
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
