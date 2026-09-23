package com.mars.cloud.nacos;

import com.mars.cloud.nacos.autoconfigure.MarsNacosDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 组件以最低优先级给出的默认值。
 *
 * <p>Spring Cloud 的注册中心健康检查（{@code discoveryComposite}）默认关闭：停机时 Nacos 先关闭客户端再等待，
 * 等待期间有人查询健康端点，这项检查就让客户端被重新创建。
 */
class NacosDefaultsTest {

    private static final String DISCOVERY_HEALTH = "spring.cloud.discovery.client.composite-indicator.enabled";

    @Test void discoveryHealthCheckIsOffByDefault() {
        StandardEnvironment environment = new StandardEnvironment();

        new MarsNacosDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DISCOVERY_HEALTH)).isEqualTo("false");
    }

    @Test void explicitConfigurationWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("explicit", Map.of(DISCOVERY_HEALTH, "true")));

        new MarsNacosDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DISCOVERY_HEALTH)).isEqualTo("true");
    }

    /** 后置处理器必须经 spring.factories 登记，否则默认值不会生效。类路径上有多份该文件，逐份查找。 */
    @Test void thePostProcessorIsRegistered() throws IOException {
        List<String> registrations = new ArrayList<>();
        for (URL url : Collections.list(getClass().getClassLoader().getResources("META-INF/spring.factories"))) {
            try (InputStream stream = url.openStream()) {
                Properties factories = new Properties();
                factories.load(stream);
                registrations.add(factories.getProperty(EnvironmentPostProcessor.class.getName(), ""));
            }
        }
        assertThat(registrations).anyMatch(value -> value.contains(MarsNacosDefaultsEnvironmentPostProcessor.class.getName()));
    }
}
