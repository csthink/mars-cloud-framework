package com.mars.cloud.observability;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.mars.cloud.observability.registry.ManagementPortRegistrationCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口写进服务实例元数据。实例监控按这个键取 Actuator 端点，
 * 缺它就会打到业务端口上，结果是一直取不到。
 *
 * <p>服务发现组件注册前自己也按 {@code management.server.port} 写这个键，随机管理端口时写的是 0。
 * 本组件的定制器在它之后执行，结果以定制器为准；后两条用例走服务发现组件真实的注册初始化。
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

    /** 经服务发现组件的注册初始化：它先写入管理端口，定制器随后执行，结果是约定的端口。 */
    @Test void theRegistrationKeepsTheConventionalManagementPort() {
        assertThat(initializedRegistration("9103").getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "9103");
    }

    /** 经服务发现组件的注册初始化：随机管理端口时它写入 0，定制器把这一项去掉，实例监控不会连向 0 端口。 */
    @Test void theRegistrationCarriesNoPortForARandomManagementPort() {
        assertThat(initializedRegistration("0").getMetadata())
                .doesNotContainKey(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY);
    }

    private static NacosRegistration initializedRegistration(String managementPort) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("management.server.port", managementPort);
        ManagementServerProperties management = new ManagementServerProperties();
        management.setPort(Integer.valueOf(managementPort));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        context.registerBean(ManagementServerProperties.class, () -> management);
        context.refresh();
        NacosDiscoveryProperties properties = new NacosDiscoveryProperties();
        properties.setService("probe");
        NacosRegistration registration = new NacosRegistration(
                List.of(new ManagementPortRegistrationCustomizer(environment)), properties, context);
        registration.init();
        context.close();
        return registration;
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
