package com.mars.cloud.observability.autoconfigure;

import com.mars.cloud.observability.registry.ManagementPortRegistrationCustomizer;
import com.mars.cloud.observability.security.ReactiveManagementSecurityConfiguration;
import com.mars.cloud.observability.security.ServletManagementSecurityConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * 可观测性组件的装配入口。
 *
 * <p>链路追踪、指标与结构化日志由 Spring Boot 自己的自动配置完成，本组件只负责三件事：
 * 把默认值与推导出的管理端口写进环境（见 {@link MarsObservabilityDefaultsEnvironmentPostProcessor}）、
 * 在启动期核验这些约定没有被配错、给管理端点加一条只匹配 Actuator 的认证链。
 *
 * <p>声明顺序排在 Spring Security 的自动配置之后：安全组件的链带
 * {@code @ConditionalOnMissingBean(SecurityFilterChain.class)} 条件，本链必须在它之后登记，
 * 两条链才能共存。类名以字符串给出，避免对安全组件产生编译期依赖。
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.security.autoconfigure.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.reactive.ReactiveSecurityAutoConfiguration",
        "com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration",
        "com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration"
})
@EnableConfigurationProperties(ObservabilityProperties.class)
public class MarsObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObservabilityConventionVerifier marsObservabilityConventionVerifier(Environment environment,
                                                                       ObservabilityProperties properties) {
        return new ObservabilityConventionVerifier(environment, properties);
    }

    /** 管理端口写进服务实例元数据；没有注册中心时这段不装配。 */
    @AutoConfiguration
    @ConditionalOnClass(name = "com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer")
    static class RegistrationMetadataConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ManagementPortRegistrationCustomizer marsManagementPortRegistrationCustomizer(Environment environment) {
            return new ManagementPortRegistrationCustomizer(environment);
        }
    }

    /** Servlet 栈的管理端点认证链；缺凭据时不装配，暴露面已由环境后处理收窄。 */
    @AutoConfiguration
    @ConditionalOnClass(name = "org.springframework.security.config.annotation.web.builders.HttpSecurity")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnManagementCredentials
    @Import(ServletManagementSecurityConfiguration.class)
    static class ServletManagementSecurityAutoConfiguration {
    }

    /** 响应式栈的管理端点认证链。 */
    @AutoConfiguration
    @ConditionalOnClass(name = "org.springframework.security.config.web.server.ServerHttpSecurity")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnManagementCredentials
    @Import(ReactiveManagementSecurityConfiguration.class)
    static class ReactiveManagementSecurityAutoConfiguration {
    }
}
