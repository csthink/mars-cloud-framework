package com.mars.cloud.observability.registry;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import com.mars.cloud.observability.internal.ManagementPort;
import org.springframework.core.env.Environment;

/**
 * 注册到 Nacos 之前把管理端口写进实例元数据。
 *
 * <p>实例监控按元数据里的 {@code management.port} 去取 Actuator 端点；缺这一项时它会
 * 退回实例的业务端口，而业务端口上没有 Actuator 端点，结果是一直 404。
 * 键名是实例监控的既有约定，不是本项目自造的。
 */
public class ManagementPortRegistrationCustomizer implements NacosRegistrationCustomizer {

    /** 实例监控读取管理端口的元数据键。 */
    public static final String MANAGEMENT_PORT_METADATA_KEY = "management.port";

    private final Environment environment;

    public ManagementPortRegistrationCustomizer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(NacosRegistration registration) {
        Integer managementPort = ManagementPort.managementPort(environment);
        if (managementPort == null || managementPort <= 0) {
            // 管理端点与业务端点共用端口时不写元数据，实例监控退回业务端口正好是对的。
            return;
        }
        registration.getMetadata().put(MANAGEMENT_PORT_METADATA_KEY, String.valueOf(managementPort));
    }
}
