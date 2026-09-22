package com.mars.cloud.rocketmq.autoconfigure;

import com.mars.cloud.common.messaging.MessagingNames;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 两组属性源：环境变量映射（最高优先级）与约定默认值（最低优先级）。
 *
 * <p>环境变量 {@code ROCKETMQ_NAME_SERVER}、{@code MARS_MQ_PREFIX}、{@code MARS_ROCKETMQ_TOPOLOGY} 只在存在时映射，
 * 运行环境相关取值只从环境变量来。默认值把进程内重试关掉、RocketMQ 重投次数设为 16、每个 binding 的消息轨迹关掉。
 * 有前缀时还把每个 {@code spring.cloud.stream.rocketmq.bindings.<binding>.producer.group} 加上前缀。
 *
 * @since 2026-09-22
 */
public final class MarsRocketMqDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEFAULTS_SOURCE = "marsRocketMqDefaults";
    static final String ENVIRONMENT_SOURCE = "marsRocketMqEnvironment";
    static final String PRODUCER_GROUP_SOURCE = "marsRocketMqProducerGroups";
    static final Pattern PRODUCER_GROUP_KEY =
            Pattern.compile("^spring\\.cloud\\.stream\\.rocketmq\\.bindings\\.[^.]+\\.producer\\.group$");

    static final String NAME_SERVER_VARIABLE = "ROCKETMQ_NAME_SERVER";
    static final String PREFIX_VARIABLE = "MARS_MQ_PREFIX";
    static final String TOPOLOGY_VARIABLE = "MARS_ROCKETMQ_TOPOLOGY";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(DEFAULTS_SOURCE)) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("spring.cloud.stream.default.consumer.max-attempts", 1);
            defaults.put("spring.cloud.stream.rocketmq.default.consumer.push.max-reconsume-times", 16);
            defaults.put("spring.cloud.stream.rocketmq.binder.enable-msg-trace", false);
            // binder 按每个 binding 自己的属性决定是否开启消息轨迹，binder 级的那条不会下发到 binding
            defaults.put("spring.cloud.stream.rocketmq.default.producer.enable-msg-trace", false);
            defaults.put("spring.cloud.stream.rocketmq.default.consumer.enable-msg-trace", false);
            environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults));
        }
        if (!environment.getPropertySources().contains(ENVIRONMENT_SOURCE)) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            map(environment, NAME_SERVER_VARIABLE, "spring.cloud.stream.rocketmq.binder.name-server", mapped);
            map(environment, PREFIX_VARIABLE, BindingPrefixApplier.PREFIX_PROPERTY, mapped);
            map(environment, TOPOLOGY_VARIABLE, "mars.rocketmq.topology", mapped);
            if (!mapped.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(ENVIRONMENT_SOURCE, mapped));
            }
        }
        if (!environment.getPropertySources().contains(PRODUCER_GROUP_SOURCE)) {
            Map<String, Object> prefixed = prefixProducerGroups(environment);
            if (!prefixed.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(PRODUCER_GROUP_SOURCE, prefixed));
            }
        }
    }

    /**
     * 生产者组也要带运行环境前缀：broker 按生产者组把事务回查路由到该组的任一连接，两个运行环境共用组名会把回查送错进程。
     * 生产者组在 binder 的扩展属性里，主上下文的 BeanPostProcessor 改不到 binder 子上下文，所以在环境层改写。
     */
    private static Map<String, Object> prefixProducerGroups(ConfigurableEnvironment environment) {
        String prefix = environment.getProperty(BindingPrefixApplier.PREFIX_PROPERTY, "").trim();
        Map<String, Object> prefixed = new LinkedHashMap<>();
        if (prefix.isEmpty() || !MessagingNames.PREFIX.matcher(prefix).matches()) {
            return prefixed;
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                if (!PRODUCER_GROUP_KEY.matcher(key).matches() || prefixed.containsKey(key)) {
                    continue;
                }
                String value = environment.getProperty(key, "").trim();
                if (MessagingNames.isName(value) && !value.startsWith(prefix)) {
                    prefixed.put(key, prefix + value);
                }
            }
        }
        return prefixed;
    }

    private static void map(ConfigurableEnvironment environment, String variable, String property, Map<String, Object> target) {
        String value = environment.getProperty(variable);
        if (value != null) {
            target.put(property, value.trim());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
