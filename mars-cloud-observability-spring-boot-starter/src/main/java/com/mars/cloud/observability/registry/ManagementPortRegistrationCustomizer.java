package com.mars.cloud.observability.registry;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import com.mars.cloud.observability.internal.ManagementAddress;
import com.mars.cloud.observability.internal.ManagementPort;
import org.springframework.core.env.Environment;

/**
 * 注册到 Nacos 之前把管理端口写进实例元数据。
 *
 * <p>实例监控按元数据里的 {@code management.port} 去取 Actuator 端点；缺这一项时它会
 * 退回实例的业务端口，而业务端口上没有 Actuator 端点，结果是一直 404。
 * 键名是实例监控的既有约定，不是本项目自造的。
 *
 * <p>服务发现组件注册前自己也按 {@code management.server.port} 写这一项，随机管理端口时写的是 0。
 * 本定制器在它之后执行，不依赖它是否写、写了什么：管理端口是正整数时写入，否则去掉这一项。
 *
 * <p>配置了管理端口的绑定地址时，服务发现组件还写入 {@code management.address}，实例监控优先按它连接管理端点。
 * 由 starter 从业务端口地址推导出的绑定地址只决定管理端口监听在哪里，不对外公布：本定制器去掉这一项，
 * 实例监控按注册地址连接。注册地址与绑定地址不同（例如显式配置了注册地址）时，公布绑定地址会让实例监控连不上；
 * 显式配置的管理地址是部署者的选择，保留服务发现组件写入的值。
 */
public class ManagementPortRegistrationCustomizer implements NacosRegistrationCustomizer {

    /** 实例监控读取管理端口的元数据键。 */
    public static final String MANAGEMENT_PORT_METADATA_KEY = "management.port";
    /** 实例监控读取管理地址的元数据键；没有这一项时它用实例的注册地址。 */
    public static final String MANAGEMENT_ADDRESS_METADATA_KEY = "management.address";

    private final Environment environment;

    public ManagementPortRegistrationCustomizer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(NacosRegistration registration) {
        if (ManagementAddress.derived(environment)) {
            registration.getMetadata().remove(MANAGEMENT_ADDRESS_METADATA_KEY);
        }
        Integer managementPort = ManagementPort.managementPort(environment);
        if (managementPort == null || managementPort <= 0) {
            // 共用端口时实例监控退回业务端口正好是对的；随机管理端口在注册时还不确定，
            // 留下服务发现组件写的 0 只会让实例监控连向 0 端口。
            registration.getMetadata().remove(MANAGEMENT_PORT_METADATA_KEY);
            return;
        }
        registration.getMetadata().put(MANAGEMENT_PORT_METADATA_KEY, String.valueOf(managementPort));
    }
}
