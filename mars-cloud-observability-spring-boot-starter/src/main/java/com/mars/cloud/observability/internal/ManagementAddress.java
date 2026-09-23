package com.mars.cloud.observability.internal;

import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.net.InetAddress;

/**
 * 管理端口绑定地址的推导规则：业务端口绑定在具体的 IP 地址上、且管理端口独立时，管理端口绑定同一个地址。
 *
 * <p>推导值放在一个单独的属性源里，排在配置文件、环境变量、命令行参数与配置中心之后，这些来源的显式配置都覆盖它；
 * 应用代码设置的默认属性在推导时已经可见，同样不会被覆盖。上下文刷新时才加入的 {@code @PropertySource}
 * 排在它之后，同名值不生效。推导值只决定管理端口绑定在哪里，不作为对外公布的管理地址，
 * 见 {@link #derived(Environment)}。
 */
public final class ManagementAddress {

    /** 管理端口的绑定地址。Spring Boot 不让它继承 {@code server.address}。 */
    public static final String PROPERTY = "management.server.address";
    /** 存放推导值的属性源名称。 */
    public static final String DERIVED_PROPERTY_SOURCE_NAME = "marsDerivedManagementAddress";

    private ManagementAddress() {
    }

    /**
     * 取值是具体的 IP 地址字面量时返回它的规范形式，否则返回 {@code null}。
     *
     * <p>{@link InetAddress#ofLiteral} 只解析字面量，不做域名解析；主机名、空值与通配地址
     * （{@code 0.0.0.0}、{@code ::}）都不算具体地址。规范形式去掉 IPv6 的方括号，
     * 并把 {@code 127.1}、{@code 010.001.002.003} 这类写法统一成四段十进制，避免不同程序对同一写法解读不同。
     */
    public static String specificLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(value.trim());
            return address.isAnyLocalAddress() ? null : address.getHostAddress();
        }
        catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }

    /**
     * 生效的管理地址是否为推导值：环境里第一个提供这一项的属性源是推导值所在的属性源。
     * Spring Boot 附加在最前面的汇总属性源包含全部来源，判断时跳过它。
     */
    public static boolean derived(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return false;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            if (source.containsProperty(PROPERTY)) {
                return DERIVED_PROPERTY_SOURCE_NAME.equals(source.getName());
            }
        }
        return false;
    }
}
