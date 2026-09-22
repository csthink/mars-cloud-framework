package com.mars.cloud.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配边界：本组件不依赖公共库、安全组件与服务发现组件的类，
 * 也不要求部署物一定有它们。四个自动配置类都要登记，漏登记的那条不会生效。
 */
class AutoConfigurationBoundaryTest {

    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test void registersEveryAutoConfiguration() throws IOException {
        List<String> registered = readImports();
        assertThat(registered).containsExactlyInAnyOrder(
                "com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration",
                "com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration$RegistrationMetadataConfiguration",
                "com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration$ServletManagementSecurityAutoConfiguration",
                "com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration$ReactiveManagementSecurityAutoConfiguration");
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
