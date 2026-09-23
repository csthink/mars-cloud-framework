package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
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
 *
 * <p>只有显式配置的 {@code spring.cloud.nacos.discovery.ip}（包括空值）阻止推导。网卡、IP 类型与
 * {@code spring.cloud.inetutils.*} 网卡偏好只能从本机网卡里挑地址：业务端口只在 {@code server.address} 上监听，
 * 挑出别的地址也没有进程在那里监听，所以推导出注册地址后它们不再影响注册地址。
 */
public final class MarsNacosDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "marsNacosDefaults";
    /** 注册到 Nacos 的实例地址。 */
    public static final String DISCOVERY_IP_PROPERTY = "spring.cloud.nacos.discovery.ip";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.cloud.discovery.client.composite-indicator.enabled", false);
        String serverAddress = specificIpv4Literal(environment.getProperty("server.address"));
        if (serverAddress != null && !environment.containsProperty(DISCOVERY_IP_PROPERTY)) {
            defaults.put(DISCOVERY_IP_PROPERTY, serverAddress);
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * 取值是具体的 IPv4 地址字面量时返回它的规范形式（四段十进制），否则返回 {@code null}。
     *
     * <p>{@link InetAddress#ofLiteral} 只解析字面量，不做域名解析。主机名、空值与通配地址都不推导，
     * 因为通配地址注册成实例地址后调用方连不上。IPv6 地址也不推导：Spring Cloud 把注册地址直接拼进
     * {@code http://<地址>:<端口>} 形式的实例地址，不给 IPv6 地址加方括号，拼出的地址无效。
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
