package com.mars.cloud.observability;

import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口的绑定地址由业务端口的地址推导：Spring Boot 的管理端口不继承 {@code server.address}，
 * 没有这一项时独立的管理端口绑定全部网卡。推导只在管理端口独立、业务地址是具体 IP 字面量、
 * 且没人显式配置管理地址时发生。
 */
class ManagementAddressDerivationTest {

    private static final String ADDRESS = MarsObservabilityDefaultsEnvironmentPostProcessor.MANAGEMENT_ADDRESS_PROPERTY;

    /** 管理端口由业务端口推导且独立时，管理端口绑定业务端口的地址。 */
    @Test void followsTheServerAddressWhenTheManagementPortIsSeparate() {
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", "127.0.0.1")))
                .isEqualTo("127.0.0.1");
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", "10.1.2.3")))
                .isEqualTo("10.1.2.3");
    }

    /** IPv6 的具体地址同样推导。 */
    @Test void followsASpecificIpv6Address() {
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", "fd00::1")))
                .isEqualTo("fd00::1");
    }

    /** 随机的独立管理端口（0）按 Spring Boot 的判定是独立端口，同样推导。 */
    @Test void followsTheServerAddressForARandomSeparateManagementPort() {
        assertThat(managementAddress(Map.of(
                "server.port", "0", "management.server.port", "0", "server.address", "127.0.0.1")))
                .isEqualTo("127.0.0.1");
    }

    /** 显式配置的管理地址保留原值，需要不同地址的部署不受推导影响。 */
    @Test void keepsAnExplicitManagementAddress() {
        assertThat(managementAddress(Map.of(
                "server.port", "8103", "server.address", "127.0.0.1", ADDRESS, "10.9.9.9")))
                .isEqualTo("10.9.9.9");
    }

    /** 偏移量为 0 时管理端点与业务端点共用端口，这时配置管理地址会启动失败，所以不推导。 */
    @Test void doesNotDeriveWhenTheManagementPortIsShared() {
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", "127.0.0.1",
                "mars.observability.management.port-offset", "0"))).isNull();
    }

    /** 显式配置的管理端口等于业务端口时同样是共用端口。 */
    @Test void doesNotDeriveWhenTheExplicitManagementPortEqualsTheServerPort() {
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", "127.0.0.1",
                "management.server.port", "8103"))).isNull();
    }

    /** 业务端口随机且没有配置管理端口时，管理端点留在业务端口上，不推导。 */
    @Test void doesNotDeriveForARandomServerPortWithoutManagementPort() {
        assertThat(managementAddress(Map.of("server.port", "0", "server.address", "127.0.0.1"))).isNull();
    }

    /** 业务地址不是具体的 IP 字面量时不推导：通配地址、主机名或空值。主机名不做域名解析。 */
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "0:0:0:0:0:0:0:0", "localhost", "service.example", " "})
    void doesNotDeriveFromAnAddressThatIsNotASpecificLiteral(String serverAddress) {
        assertThat(managementAddress(Map.of("server.port", "8103", "server.address", serverAddress))).isNull();
    }

    /** 没有配置业务地址时不推导，管理端口保持 Spring Boot 的默认。 */
    @Test void doesNotDeriveWithoutAServerAddress() {
        assertThat(managementAddress(Map.of("server.port", "8103"))).isNull();
    }

    /** 直接驱动环境后处理器，返回它写进环境的管理地址。 */
    private static String managementAddress(Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        new LinkedHashMap<>(properties).forEach(environment::setProperty);
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty(ADDRESS);
    }
}
