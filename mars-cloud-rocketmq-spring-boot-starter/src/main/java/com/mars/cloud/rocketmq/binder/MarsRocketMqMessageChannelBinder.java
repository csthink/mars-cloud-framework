package com.mars.cloud.rocketmq.binder;

import com.alibaba.cloud.stream.binder.rocketmq.RocketMQMessageChannelBinder;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQBinderConfigurationProperties;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQProducerProperties;
import com.alibaba.cloud.stream.binder.rocketmq.provisioning.RocketMQTopicProvisioner;
import com.alibaba.cloud.stream.binder.rocketmq.utils.RocketMQUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/** 普通生产与消费复用 SCA，仅事务生产使用启动前安装监听器的 handler。 */
public final class MarsRocketMqMessageChannelBinder extends RocketMQMessageChannelBinder implements DisposableBean {
    private final RocketMQBinderConfigurationProperties configuration;
    private final java.util.Map<String, MarsTransactionMessageHandler> handlers = new java.util.LinkedHashMap<>();

    public MarsRocketMqMessageChannelBinder(RocketMQBinderConfigurationProperties configuration,
            RocketMQExtendedBindingProperties extended, RocketMQTopicProvisioner provisioner) {
        super(configuration, extended, provisioner);
        this.configuration = configuration;
    }

    @Override
    protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
            ExtendedProducerProperties<RocketMQProducerProperties> properties,
            MessageChannel channel, MessageChannel errorChannel) throws Exception {
        if (!"Trans".equalsIgnoreCase(properties.getExtension().getProducerType())) {
            return super.createProducerMessageHandler(destination, properties, channel, errorChannel);
        }
        if (!properties.getExtension().getEnabled()) {
            throw new IllegalStateException("事务 producer 不允许禁用: " + properties.getBindingName());
        }
        var merged = RocketMQUtils.mergeRocketMQProperties(configuration, properties.getExtension());
        var handler = new MarsTransactionMessageHandler(destination.getName(), merged,
                errorChannel, getErrorMessageStrategy());
        handler.setApplicationContext(getApplicationContext());
        handler.setBeanFactory(getApplicationContext().getBeanFactory());
        synchronized (handlers) { handlers.put(properties.getBindingName(), handler); }
        return handler;
    }

    /** 核对实际创建的事务 handler，避免具名 binder 的局部配置改变生产类型或组。 */
    public boolean transactionRunning(String binding, String group, String listener) {
        synchronized (handlers) {
            var handler = handlers.get(binding);
            return handler != null && handler.matches(group, listener) && handler.isRunning();
        }
    }

    @Override
    public void destroy() {
        synchronized (handlers) {
            handlers.values().forEach(MarsTransactionMessageHandler::destroy);
            handlers.clear();
        }
    }
}
