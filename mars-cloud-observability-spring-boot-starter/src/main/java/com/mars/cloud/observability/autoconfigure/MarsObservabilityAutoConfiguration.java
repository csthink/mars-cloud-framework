package com.mars.cloud.observability.autoconfigure;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 可观测性组件的装配入口。
 *
 * <p>链路追踪、指标与结构化日志由 Spring Boot 自己的自动配置完成，本组件只负责三件事：
 * 把默认值与推导出的管理端口写进环境（见 {@link MarsObservabilityDefaultsEnvironmentPostProcessor}）、
 * 在启动期核验这些约定没有被配错、给管理端点加一条只匹配 Actuator 端点的认证链。
 *
 * <p>认证链与实例元数据分别是独立的自动配置类而不是本类的嵌套类：嵌套的配置类会随外层一起被处理，
 * {@code spring.autoconfigure.exclude} 对它们无效，部署物就没法单独关掉其中一项。
 */
@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class MarsObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObservabilityConventionVerifier marsObservabilityConventionVerifier(Environment environment,
                                                                       ObservabilityProperties properties) {
        return new ObservabilityConventionVerifier(environment, properties);
    }

    /**
     * 管理端点暴露面与认证链一致的核验，只对 Web 应用装配，判断方式与两条认证链的 Web 应用条件相同。
     * 不提供覆盖点：它是生效的暴露清单与认证链之间的最后一道检查。
     */
    @Bean
    @ConditionalOnWebApplication
    ManagementChainVerifier marsManagementChainVerifier(Environment environment, ListableBeanFactory beanFactory) {
        return new ManagementChainVerifier(environment, beanFactory);
    }

    /**
     * 部署物打开延迟初始化时，两个核验器仍在启动期创建：没有别的 bean 依赖它们，延迟后核验会静默跳过。
     * 声明为静态方法，筛选器在其他 bean 处理之前就能取到，不需要先创建本配置类。
     */
    @Bean
    static LazyInitializationExcludeFilter marsObservabilityVerifiersExcludedFromLazyInitialization() {
        return LazyInitializationExcludeFilter.forBeanTypes(ObservabilityConventionVerifier.class,
                ManagementChainVerifier.class);
    }
}
