package com.mars.cloud.observability;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.internal.ManagementAddress;
import com.mars.cloud.observability.registry.ManagementPortRegistrationCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端口写进服务实例元数据。实例监控按这个键取 Actuator 端点，
 * 缺它就会打到业务端口上，结果是一直取不到。
 *
 * <p>服务发现组件注册前自己也按 {@code management.server.port} 写这个键，随机管理端口时写的是 0。
 * 本组件的定制器在它之后执行，结果以定制器为准；经 {@code initializedRegistration} 的用例走服务发现组件真实的注册初始化。
 *
 * <p>管理地址同理：服务发现组件按 {@code management.server.address} 写 {@code management.address}，
 * 由 starter 推导出的绑定地址不公布，定制器去掉这一项；显式配置的管理地址保留。
 */
class ManagementPortMetadataTest {

    @Test void writesTheManagementPortIntoInstanceMetadata() {
        NacosRegistration registration = registration();
        customizer("8103", "9103").customize(registration);
        assertThat(registration.getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "9103");
    }

    /** 管理端点与业务端点共用端口时不留这一项，实例监控退回业务端口正好是对的。 */
    @Test void removesTheEntryWhenManagementSharesTheBusinessPort() {
        NacosRegistration registration = registration();
        registration.getMetadata().put(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "9103");
        customizer("8103", null).customize(registration);
        assertThat(registration.getMetadata())
                .doesNotContainKey(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY);
    }

    /** 随机管理端口在注册时尚未确定，同样不留这一项：已有的值（例如服务发现组件写入的 0）被去掉。 */
    @Test void removesTheEntryForRandomManagementPort() {
        NacosRegistration registration = registration();
        registration.getMetadata().put(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "0");
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

    /**
     * 经服务发现组件的注册初始化：推导出的管理地址只决定管理端口绑定在哪里，不写进元数据，
     * 实例监控按注册地址与管理端口连接。注册地址显式配置成另一个地址时，公布绑定地址会让实例监控连不上。
     */
    @Test void theRegistrationDoesNotPublishTheDerivedManagementAddress() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("server.address", "10.1.2.3");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        // 真实应用的环境最前面有 Spring Boot 附加的汇总属性源，它包含全部来源；判定必须跳过它。
        ConfigurationPropertySources.attach(environment);
        assertThat(environment.getProperty(ManagementAddress.PROPERTY)).isEqualTo("10.1.2.3");
        assertThat(ManagementAddress.derived(environment)).isTrue();

        assertThat(initializedRegistration(environment).getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_PORT_METADATA_KEY, "9103")
                .doesNotContainKey(ManagementPortRegistrationCustomizer.MANAGEMENT_ADDRESS_METADATA_KEY);
    }

    /** 经服务发现组件的注册初始化：显式配置的管理地址是部署者的选择，保留在元数据里。 */
    @Test void theRegistrationPublishesAnExplicitManagementAddress() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("server.address", "10.1.2.3");
        environment.setProperty(ManagementAddress.PROPERTY, "10.9.9.9");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(initializedRegistration(environment).getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_ADDRESS_METADATA_KEY, "10.9.9.9");
    }

    /**
     * 环境后处理之后、注册初始化之前加入且排在推导值前面的显式配置判定为显式配置，例如应用自己的
     * {@code ApplicationContextInitializer} 用 {@code addFirst} 加入的属性源。上下文刷新时才加入的
     * {@code @PropertySource} 排在推导值之后，不在此列。
     */
    @Test void anExplicitAddressAddedAfterTheDerivationCountsAsExplicit() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("server.address", "10.1.2.3");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        ConfigurationPropertySources.attach(environment);
        assertThat(ManagementAddress.derived(environment)).isTrue();

        environment.getPropertySources().addFirst(new MapPropertySource("initializer",
                Map.of(ManagementAddress.PROPERTY, "10.9.9.9")));

        assertThat(ManagementAddress.derived(environment)).isFalse();
        assertThat(initializedRegistration(environment).getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_ADDRESS_METADATA_KEY, "10.9.9.9");
    }

    /** 管理地址经环境变量显式给出时同样算显式配置，宽松绑定的写法也能识别。 */
    @Test void aManagementAddressFromAnEnvironmentVariableCountsAsExplicit() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("server.address", "10.1.2.3");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("MANAGEMENT_SERVER_ADDRESS", "10.9.9.9")));
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(ManagementAddress.derived(environment)).isFalse();
        assertThat(initializedRegistration(environment).getMetadata())
                .containsEntry(ManagementPortRegistrationCustomizer.MANAGEMENT_ADDRESS_METADATA_KEY, "10.9.9.9");
    }

    private static NacosRegistration initializedRegistration(String managementPort) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "8103");
        environment.setProperty("management.server.port", managementPort);
        return initializedRegistration(environment);
    }

    private static NacosRegistration initializedRegistration(MockEnvironment environment) {
        ManagementServerProperties management = new ManagementServerProperties();
        management.setPort(environment.getRequiredProperty("management.server.port", Integer.class));
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
