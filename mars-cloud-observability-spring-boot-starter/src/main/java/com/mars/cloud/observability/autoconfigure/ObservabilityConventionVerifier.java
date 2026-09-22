package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.internal.ManagementPort;
import com.mars.cloud.observability.security.ManagementCredentials;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 在启动期核验管理端口与管理端点的约定。不满足即启动失败，异常消息写出实际值与期望值。
 *
 * <p>凭据缺失或 classpath 上没有 Spring Security 时，开发 profile 收窄暴露面并告警，
 * 其他 profile 启动失败：没有认证的指标端点等于把运行细节放在内网任人读取。
 */
public final class ObservabilityConventionVerifier implements InitializingBean {

    /** 缺少凭据时的告警。这两条消息是固定的，日志策略按它们登记理由。 */
    static final String MISSING_CREDENTIALS_MESSAGE =
            "管理端点缺少认证凭据，暴露面已收窄；生产环境必须提供 mars.observability.management.username 与 password";
    static final String MISSING_SECURITY_MESSAGE =
            "classpath 上没有 Spring Security，管理端点无法建立认证链，暴露面已收窄";
    /** 有凭据、有 Spring Security，但部署物没有启用 Web 安全，链因此没建起来。 */
    static final String CHAIN_NOT_BUILT_MESSAGE =
            "管理端点的认证链没有装配：部署物没有启用 Web 安全，管理端点当前不受认证保护";

    private static final Log logger = LogFactory.getLog(ObservabilityConventionVerifier.class);

    private final Environment environment;
    private final ObservabilityProperties properties;
    private final ListableBeanFactory beanFactory;

    public ObservabilityConventionVerifier(Environment environment, ObservabilityProperties properties,
                                           ListableBeanFactory beanFactory) {
        this.environment = environment;
        this.properties = properties;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterPropertiesSet() {
        verifyManagementPort();
        verifyCredentials();
        verifyChainIsBuilt();
    }

    private void verifyManagementPort() {
        int offset = properties.getManagement().getPortOffset();
        Integer serverPort = ManagementPort.serverPort(environment);
        Integer managementPort = ManagementPort.managementPort(environment);

        require(managementPort == null || managementPort != ManagementPort.DISABLED,
                "management.server.port 不能为 -1：禁用管理端口会让实例监控看不到本实例");

        if (offset == 0) {
            require(isDevelopmentProfile(),
                    "mars.observability.management.port-offset 为 0 表示管理端点与业务端点共用端口，只允许开发 profile；"
                            + "当前激活的 profile 是 " + Arrays.toString(environment.getActiveProfiles()));
            return;
        }
        if (serverPort == null || serverPort <= 0 || managementPort == null) {
            // 业务端口是随机端口或未配置时不推导，管理端点留在业务端口上，测试即属于这种情况。
            return;
        }
        if (managementPort == ManagementPort.SAME_PORT) {
            // 显式写 0 表示让容器分配随机管理端口，真端口测试用得到。
            return;
        }
        int expected = serverPort + offset;
        require(managementPort == expected,
                "management.server.port 与约定不符：期望 " + expected + "（业务端口 " + serverPort
                        + " 加偏移量 " + offset + "），实际 " + managementPort);
    }

    private void verifyCredentials() {
        ClassLoader classLoader = getClass().getClassLoader();
        if (ManagementAccess.canAuthenticate(environment, classLoader)) {
            return;
        }
        if (!ManagementAccess.securityPresent(classLoader)) {
            // 部署物没有接入 Spring Security。
            // 告警即可；拒绝启动会让这类部署物在任何非开发环境都起不来。
            logger.warn(MISSING_SECURITY_MESSAGE);
            return;
        }
        // 有 Spring Security 却没配凭据是配置遗漏：放过它等于把指标与日志级别端点裸露在内网。
        require(isDevelopmentProfile(), MISSING_CREDENTIALS_MESSAGE + "；当前激活的 profile 是 "
                + Arrays.toString(environment.getActiveProfiles()));
        logger.warn(MISSING_CREDENTIALS_MESSAGE);
    }

    /**
     * 凭据齐备时链应当已经装配。没装配说明部署物没有启用 Web 安全，
     * 这时管理端点不受认证保护；只告警不拒绝启动，因为那同样是部署物自己的形态。
     */
    private void verifyChainIsBuilt() {
        if (!ManagementAccess.canAuthenticate(environment, getClass().getClassLoader())) {
            return;
        }
        boolean built = beanFactory.containsBeanDefinition(ManagementCredentials.SERVLET_CHAIN_BEAN)
                || beanFactory.containsBeanDefinition(ManagementCredentials.REACTIVE_CHAIN_BEAN);
        if (!built) {
            logger.warn(CHAIN_NOT_BUILT_MESSAGE);
        }
    }

    private boolean isDevelopmentProfile() {
        return ManagementAccess.developmentProfile(environment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
