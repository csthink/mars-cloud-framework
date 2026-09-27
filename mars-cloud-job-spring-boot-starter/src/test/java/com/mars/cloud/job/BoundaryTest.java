package com.mars.cloud.job;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组件的边界：自动配置清单、环境后处理登记，以及不引入 xxl-job 的 Java 构件与 Groovy。
 */
class BoundaryTest {

    private static List<String> lines(String resource) throws Exception {
        return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8).lines()
                .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    @Test
    void autoConfigurationImportsListExactlyTheEntryPoint() throws Exception {
        List<String> imports = lines("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(imports).containsExactly("com.mars.cloud.job.autoconfigure.MarsJobAutoConfiguration");
        assertThat(Class.forName(imports.getFirst())).isNotNull();
    }

    @Test
    void theEnvironmentPostProcessorIsRegistered() throws Exception {
        List<String> factories = lines("META-INF/spring.factories");
        assertThat(factories).containsExactly("org.springframework.boot.EnvironmentPostProcessor=\\",
                "com.mars.cloud.job.autoconfigure.MarsJobDefaultsEnvironmentPostProcessor");
        assertThat(Class.forName(factories.getLast())).isNotNull();
    }

    @Test
    void neitherXxlJobArtifactsNorGroovyAreOnTheClasspath() {
        assertThat(present("com.xxl.job.core.executor.XxlJobExecutor")).isFalse();
        assertThat(present("com.xxl.tool.response.Response")).isFalse();
        assertThat(present("groovy.lang.GroovyClassLoader")).isFalse();
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, BoundaryTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
