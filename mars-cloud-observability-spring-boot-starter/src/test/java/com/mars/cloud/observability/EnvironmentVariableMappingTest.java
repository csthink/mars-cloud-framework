package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环境变量到属性的映射，按真实进程的形态验证。
 *
 * <p>用真的环境变量属性源而不是普通属性：它对名字做大小写与分隔符转换，
 * 这一层转换正是「变量名到底能不能被读到」的关键，用普通属性测会得出错误结论。
 */
class EnvironmentVariableMappingTest {

    @Test void shortVariableNamesReachTheComponentProperties() {
        ConfigurableEnvironment environment = environmentWith(Map.of(
                "MARS_MANAGEMENT_USERNAME", "ops",
                "MARS_MANAGEMENT_PASSWORD", "secret"));

        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(MarsObservabilityDefaultsEnvironmentPostProcessor.USERNAME_PROPERTY))
                .isEqualTo("ops");
        assertThat(environment.getProperty(MarsObservabilityDefaultsEnvironmentPostProcessor.PASSWORD_PROPERTY))
                .isEqualTo("secret");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus,metrics,loggers,threaddump,heapdump");
    }

    @Test void theTracingEndpointVariableIsMappedTheSameWay() {
        ConfigurableEnvironment environment = environmentWith(Map.of(
                "OTLP_TRACING_ENDPOINT", "http://127.0.0.1:4318/v1/traces"));

        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(MarsObservabilityDefaultsEnvironmentPostProcessor.TRACING_ENDPOINT_PROPERTY))
                .isEqualTo("http://127.0.0.1:4318/v1/traces");
    }

    /** 管理端口的推导同样要在真实环境变量下成立。 */
    @Test void theManagementPortIsDerivedFromTheServerPortVariable() {
        ConfigurableEnvironment environment = environmentWith(Map.of("SERVER_PORT", "8103"));

        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.server.port")).isEqualTo("9103");
    }

    private static ConfigurableEnvironment environmentWith(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }
}
