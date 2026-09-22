package com.mars.cloud.rocketmq.autoconfigure;

import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQProducerProperties;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Spring Cloud Stream 的 binding 配置推出本应用的全部生产与消费 binding。
 *
 * <p>只看 {@code spring.cloud.stream.bindings} 里显式配置的 binding；名字必须形如
 * {@code <函数名>-in-<n>} 或 {@code <函数名>-out-<n>}。主题与消费组的完整清单由此推出，不另维护文件。
 *
 * @since 2026-09-22
 */
public final class RocketMqBindingCatalog {

    static final Pattern BINDING_NAME = Pattern.compile("^(.+)-(in|out)-(\\d+)$");

    private final List<RocketMqBinding> bindings;

    public RocketMqBindingCatalog(BindingServiceProperties bindingProperties,
                                  RocketMQExtendedBindingProperties extended,
                                  BindingPrefixApplier applier) {
        List<RocketMqBinding> result = new ArrayList<>();
        for (Map.Entry<String, BindingProperties> entry : bindingProperties.getBindings().entrySet()) {
            String name = entry.getKey();
            BindingProperties properties = entry.getValue();
            BindingPrefixApplier.RawNames raw = applier.rawNames().getOrDefault(name,
                    new BindingPrefixApplier.RawNames(properties.getDestination(), properties.getGroup()));
            Matcher matcher = BINDING_NAME.matcher(name);
            RocketMqBinding.Kind kind = null;
            if (matcher.matches()) {
                kind = "in".equals(matcher.group(2)) ? RocketMqBinding.Kind.CONSUMER : RocketMqBinding.Kind.PRODUCER;
            }
            if (kind == RocketMqBinding.Kind.PRODUCER) {
                RocketMQProducerProperties producer = extended.getExtendedProducerProperties(name);
                result.add(new RocketMqBinding(name, kind, properties.getDestination(), raw.rawDestination(),
                        null, null, producer.getProducerType(), producer.getGroup(), producer.getTransactionListener(),
                        0, false, producer.getEnableMsgTrace()));
            } else {
                int maxAttempts = properties.getConsumer() == null ? 3 : properties.getConsumer().getMaxAttempts();
                boolean batch = properties.getConsumer() != null && properties.getConsumer().isBatchMode();
                boolean trace = kind == null || extended.getExtendedConsumerProperties(name).getEnableMsgTrace();
                result.add(new RocketMqBinding(name, kind, properties.getDestination(), raw.rawDestination(),
                        properties.getGroup(), raw.rawGroup(), null, null, null, maxAttempts, batch, trace));
            }
        }
        this.bindings = Collections.unmodifiableList(result);
    }

    /** 全部已配置的 binding。 */
    public List<RocketMqBinding> bindings() {
        return bindings;
    }

    /** 按 binding 名查找。 */
    public Optional<RocketMqBinding> find(String name) {
        return bindings.stream().filter(binding -> binding.name().equals(name)).findFirst();
    }

    /** 全部消费 binding。 */
    public List<RocketMqBinding> consumers() {
        return bindings.stream().filter(binding -> binding.kind() == RocketMqBinding.Kind.CONSUMER).toList();
    }

    /** 全部生产 binding。 */
    public List<RocketMqBinding> producers() {
        return bindings.stream().filter(binding -> binding.kind() == RocketMqBinding.Kind.PRODUCER).toList();
    }
}
