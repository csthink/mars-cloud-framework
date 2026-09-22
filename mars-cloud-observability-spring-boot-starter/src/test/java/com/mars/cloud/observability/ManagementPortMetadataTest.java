package com.mars.cloud.observability;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.mars.cloud.observability.registry.ManagementPortRegistrationCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口写进服务实例元数据。实例监控按这个键取 Actuator 端点，
 * 缺它就会打到业务端口上，结果是一直取不到。
 */
class ManagementPortMetadataTest {

    @Test void writesTheManagementPortIntoInstanceMetadata() {
        NacosRegistration registration = registration();
        customizer("8103", "9103").customize(registration);
        assertThat(registration.getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "9103");
    }

    /** 管理端点与业务端点共用端口时不写元数据，实例监控退回业务端口正好是对的。 */
    @Test void writesNothingWhenManagementSharesTheBusinessPort() {
        NacosRegistration registration = registration();
        customizer("8103", null).customize(registration);
        assertThat(registration.getMetadata())
                .doesNotContainKey(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY);
    }

    /** 随机管理端口在注册时尚未确定，同样不写。 */
    @Test void writesNothingForRandomManagementPort() {
        NacosRegistration registration = registration();
        customizer("8103", "0").customize(registration);
        assertThat(registration.getMetadata())
                .doesNotContainKey(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY);
    }

    private static ManagementPortRegistrationCustomizer customizer(String serverPort, String managementPort) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", serverPort);
        if (managementPort != null) {
            environment.setProperty("management.server.port", managementPort);
        }
        return new ManagementPortRegistrationCustomizer(environment);
    }

    private static NacosRegistration registration() {
        NacosDiscoveryProperties properties = new NacosDiscoveryProperties();
        properties.setService("probe");
        return new NacosRegistration(List.of(), properties, null);
    }
}
