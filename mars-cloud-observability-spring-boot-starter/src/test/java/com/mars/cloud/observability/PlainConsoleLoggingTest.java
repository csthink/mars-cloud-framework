package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 控制台日志格式的开关：默认结构化，plain 时不写这个键、保留 Spring Boot 的文本格式。 */
class PlainConsoleLoggingTest {

    @Test void defaultsToStructuredConsoleOutput() {
        assertThat(consoleFormatAfterPostProcessing(null)).isEqualTo("ecs");
    }

    @Test void writesNothingWhenPlainIsRequested() {
        assertThat(consoleFormatAfterPostProcessing("plain")).isNull();
    }

    @Test void acceptsAnyCaseForPlain() {
        assertThat(consoleFormatAfterPostProcessing("PLAIN")).isNull();
    }

    private static String consoleFormatAfterPostProcessing(String requested) {
        MockEnvironment environment = new MockEnvironment();
        if (requested != null) {
            environment.setProperty("mars.observability.logging.console-format", requested);
        }
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("logging.structured.format.console");
    }
}
