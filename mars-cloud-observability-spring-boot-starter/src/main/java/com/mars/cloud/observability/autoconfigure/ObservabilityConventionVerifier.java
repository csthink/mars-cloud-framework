package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.internal.ManagementAccess;
import com.mars.cloud.observability.internal.ManagementPort;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
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

    private static final Log logger = LogFactory.getLog(ObservabilityConventionVerifier.class);

    private final Environment environment;
    private final ObservabilityProperties properties;

    public ObservabilityConventionVerifier(Environment environment, ObservabilityProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        verifyManagementPort();
        verifyCredentials();
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
        String message = ManagementAccess.securityPresent(classLoader)
                ? MISSING_CREDENTIALS_MESSAGE
                : MISSING_SECURITY_MESSAGE;
        require(isDevelopmentProfile(), message + "；当前激活的 profile 是 "
                + Arrays.toString(environment.getActiveProfiles()));
        logger.warn(message);
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
