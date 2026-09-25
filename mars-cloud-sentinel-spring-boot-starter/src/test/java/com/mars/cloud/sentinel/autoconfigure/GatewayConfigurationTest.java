package com.mars.cloud.sentinel.autoconfigure;

import com.mars.cloud.sentinel.InMemoryRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配条件：网关必须指明客户端地址所在的交换属性；没有 Nacos 配置又没有显式关闭 Sentinel 时启动失败；
 * 自动装配引用的类名都真实存在。
 */
class GatewayConfigurationTest {

    @Test
    void theGatewayRequiresTheClientAddressAttribute() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsSentinelGatewayAutoConfiguration.class))
                .withBean(RuleConfigSource.class, InMemoryRuleConfigSource::new)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("mars.sentinel.gateway.client-ip-attribute"));
    }

    @Test
    void rulesComeFromNacosUnlessSentinelIsExplicitlyDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsSentinelAutoConfiguration.class))
                .withPropertyValues("spring.application.name=no-nacos-probe")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("spring.cloud.nacos.config.enabled"));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsSentinelAutoConfiguration.class))
                .withPropertyValues("spring.cloud.sentinel.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void orderingReferencesPointAtExistingClasses() throws Exception {
        for (Class<?> type : new Class<?>[] {MarsSentinelAutoConfiguration.class, MarsSentinelGatewayAutoConfiguration.class}) {
            AutoConfiguration annotation = type.getAnnotation(AutoConfiguration.class);
            for (String name : annotation.beforeName()) {
                Class.forName(name, false, getClass().getClassLoader());
            }
            for (String name : annotation.afterName()) {
                Class.forName(name, false, getClass().getClassLoader());
            }
        }
    }
}
