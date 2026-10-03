package com.mars.cloud.rocketmq.binder;

import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQBinderConfigurationProperties;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import com.alibaba.cloud.stream.binder.rocketmq.provisioning.RocketMQTopicProvisioner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 独立 binder 类型，不导入或覆盖上游 binder 的配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RocketMQBinderConfigurationProperties.class, RocketMQExtendedBindingProperties.class})
public class MarsRocketMqBinderConfiguration {
    @Bean
    public RocketMQTopicProvisioner marsRocketMqTopicProvisioner() { return new RocketMQTopicProvisioner(); }

    @Bean
    public MarsRocketMqMessageChannelBinder marsRocketMqMessageChannelBinder(
            RocketMQBinderConfigurationProperties configuration, RocketMQExtendedBindingProperties extended,
            RocketMQTopicProvisioner provisioner) {
        return new MarsRocketMqMessageChannelBinder(configuration, extended, provisioner);
    }
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(org.springframework.boot.health.contributor.HealthIndicator.class)
    @org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator("rocketmq")
    static class HealthConfiguration {
        @Bean
        public com.alibaba.cloud.stream.binder.rocketmq.actuator.RocketMQBinderHealthIndicator rocketMQBinderHealthIndicator() {
            return new com.alibaba.cloud.stream.binder.rocketmq.actuator.RocketMQBinderHealthIndicator();
        }
    }
}
