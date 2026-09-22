package com.mars.cloud.rocketmq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖足迹与源码守卫：排除的传递依赖不得回来，框架源码不得使用 fastjson。
 */
class DependencyBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "okhttp3.OkHttpClient",
            "kotlin.Unit",
            "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter",
            "io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter",
            "org.reflections.Reflections",
            "javassist.ClassPool"
    })
    void excludedTransitiveDependenciesAreAbsent(String className) {
        assertThat(isPresent(className)).as(className).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.alibaba.cloud.stream.binder.rocketmq.RocketMQMessageChannelBinder",
            "org.apache.rocketmq.tools.admin.DefaultMQAdminExt",
            "tools.jackson.databind.ObjectMapper"
    })
    void requiredDependenciesArePresent(String className) {
        assertThat(isPresent(className)).as(className).isTrue();
    }

    @Test
    void frameworkSourcesNeverImportFastjson() throws IOException {
        Path root = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent();
        assertThat(root.resolve("pom.xml")).exists();
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> path.toString().contains("/src/main/java/") || path.toString().contains("/src/test/java/")
                            || path.toString().contains("/src/contract/java/"))
                    .filter(path -> !path.equals(Path.of(DependencyBoundaryTest.class.getProtectionDomain().getCodeSource().getLocation().getPath())))
                    .filter(path -> {
                        try {
                            return Files.readAllLines(path).stream().anyMatch(line -> line.startsWith("import com.alibaba.fastjson"));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
        assertThat(offenders).as("框架源码不得使用 fastjson，它只允许作为 RocketMQ 客户端的内部依赖存在").isEmpty();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, DependencyBoundaryTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
