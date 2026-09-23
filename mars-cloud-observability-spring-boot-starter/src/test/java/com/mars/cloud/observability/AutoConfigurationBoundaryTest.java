package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.ManagementRegistrationAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.autoconfigure.ReactiveManagementSecurityAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.ServletManagementSecurityAutoConfiguration;
import com.mars.cloud.observability.internal.ManagementAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配边界：四个自动配置类都要登记，漏登记的那条不会生效；部署物没有 Spring Security 或服务发现组件时
 * 照常装配，只是不建认证链、不写实例元数据。本组件与公共库、安全组件没有依赖关系，见模块的 POM。
 */
class AutoConfigurationBoundaryTest {

    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test void registersEveryAutoConfiguration() throws IOException {
        List<String> registered = readImports();
        assertThat(registered).containsExactlyInAnyOrder(
                "com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration",
                "com.mars.cloud.observability.autoconfigure.ManagementRegistrationAutoConfiguration",
                "com.mars.cloud.observability.autoconfigure.ServletManagementSecurityAutoConfiguration",
                "com.mars.cloud.observability.autoconfigure.ReactiveManagementSecurityAutoConfiguration");
    }

    /**
     * 每个自动配置都必须是顶层类：嵌套的配置类会随外层一起被处理，
     * {@code spring.autoconfigure.exclude} 对它们无效，部署物就关不掉其中任何一项。
     */
    @Test void everyAutoConfigurationIsATopLevelClass() throws Exception {
        for (String name : readImports()) {
            assertThat(name).doesNotContain("$");
            assertThat(Class.forName(name).getEnclosingClass()).isNull();
        }
    }

    @Test void everyRegisteredClassExists() throws Exception {
        for (String name : readImports()) {
            assertThat(Class.forName(name)).isNotNull();
        }
    }

    /** 环境后处理器必须经 spring.factories 登记，否则默认值与端口推导都不会发生。 */
    @Test void registersTheEnvironmentPostProcessor() throws IOException {
        String factories = read("META-INF/spring.factories");
        assertThat(factories)
                .contains("org.springframework.boot.EnvironmentPostProcessor")
                .contains("com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor");
    }

    /**
     * 自动配置排序声明里的类名都必须存在：指向不存在的类时排序约束静默失效，装配顺序只剩类名的字母序。
     * Boot 的类在本模块测试 classpath 上直接加载；安全组件不是本模块的依赖，按它登记的自动配置清单核对。
     */
    @Test void everyOrderingReferenceNamesAnExistingClass() throws Exception {
        List<String> securityStarter = Files.readAllLines(
                Path.of("../mars-cloud-security-spring-boot-starter/src/main/resources", IMPORTS));
        for (Class<?> type : List.of(ServletManagementSecurityAutoConfiguration.class,
                ReactiveManagementSecurityAutoConfiguration.class, ManagementRegistrationAutoConfiguration.class)) {
            AutoConfiguration ordering = type.getAnnotation(AutoConfiguration.class);
            for (String name : ordering.afterName()) {
                if (name.startsWith("com.mars.cloud.security.")) {
                    assertThat(securityStarter).as(type.getSimpleName()).contains(name);
                }
                else {
                    assertThat(Class.forName(name)).as(type.getSimpleName()).isNotNull();
                }
            }
            assertThat(ordering.beforeName()).as(type.getSimpleName()).isEmpty();
        }
    }

    /**
     * classpath 上没有 Spring Security 与服务发现组件时，四个自动配置照常处理：核验器装配，
     * 认证链与实例元数据按各自的类条件退出，上下文正常启动。
     */
    @Test void assemblesWithoutSpringSecurityOrServiceDiscovery() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.security", "com.alibaba.cloud"))
                .withInitializer(context -> {
                    SpringApplication application = new SpringApplication();
                    application.setResourceLoader(new DefaultResourceLoader(context.getClassLoader()));
                    new MarsObservabilityDefaultsEnvironmentPostProcessor()
                            .postProcessEnvironment(context.getEnvironment(), application);
                })
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class,
                        ManagementRegistrationAutoConfiguration.class,
                        ServletManagementSecurityAutoConfiguration.class,
                        ReactiveManagementSecurityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasBean("marsObservabilityConventionVerifier")
                            .hasBean("marsManagementChainVerifier")
                            .doesNotHaveBean(ManagementAccess.SERVLET_CHAIN_BEAN)
                            .doesNotHaveBean("marsManagementPortRegistrationCustomizer");
                    assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                            .isEqualTo("health,info");
                });
    }

    private static List<String> readImports() throws IOException {
        return read(IMPORTS).lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = AutoConfigurationBoundaryTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("找不到资源 " + resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
