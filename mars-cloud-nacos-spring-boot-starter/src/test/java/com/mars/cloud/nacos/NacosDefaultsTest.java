package com.mars.cloud.nacos;

import com.mars.cloud.nacos.autoconfigure.MarsNacosDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 *
 * <p>业务端口绑定具体的 IP 地址时，注册地址取同一个地址：Spring Cloud Alibaba 自己选取的是第一块非回环网卡，
 * 不参考 {@code server.address}。
 */
class NacosDefaultsTest {

    private static final String DISCOVERY_HEALTH = "spring.cloud.discovery.client.composite-indicator.enabled";
    private static final String DISCOVERY_IP = MarsNacosDefaultsEnvironmentPostProcessor.DISCOVERY_IP_PROPERTY;

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

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "fd00::1"})
    void theRegistrationAddressFollowsASpecificServerAddress(String serverAddress) {
        assertThat(registrationAddress(Map.of("server.address", serverAddress))).isEqualTo(serverAddress);
    }

    /** 显式配置的注册地址保留原值：注册地址与绑定地址不同的部署（例如容器端口映射）由它表达。 */
    @Test void anExplicitRegistrationAddressWins() {
        assertThat(registrationAddress(Map.of("server.address", "10.1.2.3", DISCOVERY_IP, "192.0.2.10")))
                .isEqualTo("192.0.2.10");
    }

    /** 业务地址不是具体的 IP 字面量时不推导：通配地址注册后调用方连不上，主机名不做域名解析。 */
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "localhost", "service.internal", " "})
    void noRegistrationAddressIsDerivedFromAnAddressThatIsNotASpecificLiteral(String serverAddress) {
        assertThat(registrationAddress(Map.of("server.address", serverAddress))).isNull();
    }

    /** 没有配置业务地址时不推导，注册地址保持 Spring Cloud Alibaba 的默认。 */
    @Test void noRegistrationAddressIsDerivedWithoutAServerAddress() {
        assertThat(registrationAddress(Map.of())).isNull();
    }

    private static String registrationAddress(Map<String, Object> explicit) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("explicit", explicit));

        new MarsNacosDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        return environment.getProperty(DISCOVERY_IP);
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
