package com.mars.cloud.observability;

import com.mars.cloud.observability.app.plain.PlainProbeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组件默认值的优先级：任何配置来源都覆盖它，唯一的例外是应用代码里设置的默认属性。
 *
 * <p>Spring Boot 在环境后处理全部完成之后才把 {@code SpringApplication#setDefaultProperties} 设置的属性源
 * 移到末尾，所以它排在组件的默认值之后，同名的组件默认值生效。
 */
class DefaultsPrecedenceTest {

    private static final String SAMPLING = "management.tracing.sampling.probability";

    @Test void configuredValuesOverrideTheComponentDefault() {
        try (ConfigurableApplicationContext context = plain().run("--" + SAMPLING + "=0.5")) {
            assertThat(context.getEnvironment().getProperty(SAMPLING)).isEqualTo("0.5");
        }
    }

    @Test void applicationDefaultPropertiesDoNotOverrideTheComponentDefault() {
        try (ConfigurableApplicationContext context = plain().properties(SAMPLING + "=0.5").run()) {
            assertThat(context.getEnvironment().getProperty(SAMPLING)).isEqualTo("1.0");
        }
    }

    private static SpringApplicationBuilder plain() {
        return new SpringApplicationBuilder(PlainProbeApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=defaults-precedence-probe",
                        "mars.observability.management.username=ops",
                        "mars.observability.management.password=ops-secret");
    }
}
