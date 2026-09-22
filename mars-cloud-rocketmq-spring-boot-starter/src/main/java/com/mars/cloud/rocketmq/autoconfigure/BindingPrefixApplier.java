package com.mars.cloud.rocketmq.autoconfigure;

import com.mars.cloud.common.messaging.MessagingNames;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在 {@link BindingServiceProperties} 初始化后给每个 binding 的 destination 与 group 加运行环境前缀。
 *
 * <p>只改写符合命名规则的值，不符合的原样留给 {@link RocketMqConventionVerifier} 报错；改写前的原值记录下来
 * 供校验与主题核验使用。重试主题与死信主题由 broker 从消费组名派生，因而自动带前缀。
 *
 * @since 2026-09-22
 */
public final class BindingPrefixApplier implements BeanPostProcessor {

    static final String PREFIX_PROPERTY = "mars.rocketmq.prefix";

    private final String prefix;
    private final Map<String, RawNames> rawNames = new LinkedHashMap<>();

    /**
     * @param rawDestination 改写前的 destination
     * @param rawGroup 改写前的 group，可能为 null
     */
    public record RawNames(String rawDestination, String rawGroup) {
    }

    public BindingPrefixApplier(Environment environment) {
        this.prefix = environment.getProperty(PREFIX_PROPERTY, "").trim();
    }

    public String prefix() {
        return prefix;
    }

    /** 每个已配置 binding 改写前的名字，按 binding 名索引。 */
    public Map<String, RawNames> rawNames() {
        return Collections.unmodifiableMap(rawNames);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof BindingServiceProperties properties) {
            properties.getBindings().forEach(this::apply);
        }
        return bean;
    }

    private void apply(String binding, BindingProperties properties) {
        String destination = properties.getDestination();
        String group = properties.getGroup();
        rawNames.put(binding, new RawNames(destination, group));
        if (prefix.isEmpty() || !MessagingNames.PREFIX.matcher(prefix).matches()) {
            return;
        }
        if (MessagingNames.isName(destination) && !destination.startsWith(prefix)) {
            properties.setDestination(prefix + destination);
        }
        if (MessagingNames.isName(group) && !group.startsWith(prefix)) {
            properties.setGroup(prefix + group);
        }
    }
}
