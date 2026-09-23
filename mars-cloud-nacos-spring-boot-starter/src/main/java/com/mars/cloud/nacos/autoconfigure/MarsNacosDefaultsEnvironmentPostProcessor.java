package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 以最低优先级提供 Nacos 相关的默认值；任何显式配置（环境变量、配置中心、application.yml）都覆盖它。
 *
 * <p>关闭 Spring Cloud 的注册中心健康检查（{@code discoveryComposite}）。停机时 Nacos 的优雅停机先注销并关闭
 * Nacos 客户端，再按 {@code spring.cloud.nacos.discovery.graceful-shutdown-wait-time}（默认 10 秒）等待；
 * 等待期间有人查询健康端点（例如实例监控的轮询），这项检查查询注册中心，Nacos 客户端就被重新创建，
 * 停机中途重新连接 Nacos。这项检查只在根健康端点里，不在存活与就绪探针组里。
 *
 * <p>业务端口绑定在具体的 IPv4 地址上时，注册到 Nacos 的地址取同一个地址。没有配置注册地址时，
 * Spring Cloud Alibaba 注册第一块非回环网卡的地址，不参考 {@code server.address}；
 * 业务端口只绑定在另一个地址上时，调用方按注册地址连接会失败。
 */
public final class MarsNacosDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "marsNacosDefaults";
    /** 注册到 Nacos 的实例地址。 */
    public static final String DISCOVERY_IP_PROPERTY = "spring.cloud.nacos.discovery.ip";

    /** 这些配置项表达了部署者对注册地址怎么选取的决定；配置了任何一项就不推导。 */
    private static final List<String> REGISTRATION_ADDRESS_CHOICES = List.of(
            DISCOVERY_IP_PROPERTY,
            "spring.cloud.nacos.discovery.network-interface",
            "spring.cloud.nacos.discovery.ip-type",
            "spring.cloud.inetutils.use-only-site-local-interfaces");
    /** 同样表达注册地址选取方式的列表配置项。 */
    private static final List<String> REGISTRATION_ADDRESS_CHOICE_LISTS = List.of(
            "spring.cloud.inetutils.preferred-networks",
            "spring.cloud.inetutils.ignored-interfaces");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.cloud.discovery.client.composite-indicator.enabled", false);
        String serverAddress = specificIpv4Literal(environment.getProperty("server.address"));
        if (serverAddress != null && !registrationAddressChosen(environment)) {
            defaults.put(DISCOVERY_IP_PROPERTY, serverAddress);
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * 部署者是否已经决定了注册地址怎么选取：显式的注册地址、网卡、IP 类型，或 Spring Cloud 的网卡偏好。
     * 注册地址一旦由本组件给出，Spring Cloud Alibaba 就不再看这些配置项，所以配置了任何一项都不推导。
     * 用 {@link Binder} 判断，环境变量、列表写法与带下标的写法都能识别。
     */
    private static boolean registrationAddressChosen(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        return REGISTRATION_ADDRESS_CHOICES.stream()
                        .anyMatch(name -> binder.bind(name, Bindable.of(String.class)).isBound())
                || REGISTRATION_ADDRESS_CHOICE_LISTS.stream()
                        .anyMatch(name -> binder.bind(name, Bindable.listOf(String.class)).isBound());
    }

    /**
     * 取值是具体的 IPv4 地址字面量时返回它的规范形式（四段十进制），否则返回 {@code null}。
     *
     * <p>{@link InetAddress#ofLiteral} 只解析字面量，不做域名解析。主机名、空值与通配地址都不推导，
     * 因为通配地址注册成实例地址后调用方连不上。IPv6 地址也不推导：注册地址会被直接拼进
     * {@code http://<地址>:<端口>} 形式的实例地址，IPv6 地址不带方括号时拼出的地址无效，IPv6 部署显式配置注册地址。
     */
    private static String specificIpv4Literal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(value.trim());
            return address instanceof Inet4Address && !address.isAnyLocalAddress() ? address.getHostAddress() : null;
        }
        catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }
}
