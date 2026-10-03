package com.mars.cloud.rocketmq.autoconfigure;

import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.config.BinderProperties;
import org.springframework.cloud.stream.utils.CacheKeyCreatorUtils;
import org.springframework.context.SmartLifecycle;

/** 在 Stream 输出绑定启动之后、应用就绪之前检查实际事务绑定。 */
public final class TransactionBindingLifecycle implements SmartLifecycle, org.springframework.beans.factory.InitializingBean {
    private final RocketMqBindingCatalog catalog;
    private final BindingServiceProperties properties;
    private final BindingService service;
    private final org.springframework.cloud.stream.binder.BinderFactory factory;
    private volatile boolean running;

    public TransactionBindingLifecycle(RocketMqBindingCatalog catalog, BindingServiceProperties properties, BindingService service, org.springframework.cloud.stream.binder.BinderFactory factory) {
        this.catalog = catalog;
        this.properties = properties;
        this.service = service;
        this.factory = factory;
    }

    static String binderType(String binding, BindingServiceProperties properties) {
        String name = properties.getBinder(binding);
        if (name == null || name.isBlank()) name = properties.getDefaultBinder();
        if (name == null || name.isBlank()) name = "mars-rocketmq";
        BinderProperties configured = properties.getBinders().get(name);
        return configured != null && configured.getType() != null && !configured.getType().isBlank()
                ? configured.getType() : name;
    }

    @Override
    public void afterPropertiesSet() {
        for (var item : catalog.producers()) {
            if (!item.transactional()) continue;
            String type = binderType(item.name(), properties);
            RocketMqConventionVerifier.require("mars-rocketmq".equals(type) || "integration".equals(type),
                    "事务 binding [" + item.name() + "] 必须使用 mars-rocketmq binder，收到: " + type);
        }
    }

    @Override
    public void start() {
        for (var item : catalog.producers()) {
            if (!item.transactional() || "integration".equals(binderType(item.name(), properties))) continue;
            var actual = factory.getBinder(properties.getBinder(item.name()), org.springframework.messaging.MessageChannel.class);
            RocketMqConventionVerifier.require(actual instanceof com.mars.cloud.rocketmq.binder.MarsRocketMqMessageChannelBinder mars
                            && mars.transactionRunning(item.name(), item.producerGroup(), item.transactionListener()),
                    "事务 binding [" + item.name() + "] 的实际事务 producer 类型、组或监听器与应用配置不一致，或尚未启动");
            var binding = service.getProducerBinding(CacheKeyCreatorUtils.createChannelCacheKey(item.name(), properties));
            RocketMqConventionVerifier.require(binding != null && binding.isRunning(),
                    "事务 binding [" + item.name() + "] 未启动：必须在启动阶段建立实际输出绑定并成功启动 producer");
        }
        running = true;
    }

    @Override
    public int getPhase() { return Integer.MIN_VALUE + 1001; }
    @Override
    public boolean isRunning() { return running; }
    @Override
    public void stop() { running = false; }
}
