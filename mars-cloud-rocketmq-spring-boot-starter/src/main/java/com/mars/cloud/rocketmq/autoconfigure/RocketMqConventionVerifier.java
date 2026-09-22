package com.mars.cloud.rocketmq.autoconfigure;

import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQBinderConfigurationProperties;
import com.mars.cloud.common.messaging.MessagingNames;
import com.mars.cloud.rocketmq.internal.MarsTransactionListener;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import com.mars.cloud.rocketmq.topology.RocketMqTopologyManager;
import com.mars.cloud.rocketmq.topology.RocketMqTopologyMode;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 启动期核对消息约定，不满足即启动失败。
 *
 * <p>核对项：名字服务器显式配置、binder 与每个 binding 的消息轨迹关闭、运行环境前缀形态、binding 名形态、主题名形态、配置里不写前缀、
 * 消费组等于应用名加主题、进程内重试关闭、不用批量消费、事务生产者指向 starter 的监听器且有唯一的回查检查器、
 * 函数定义里的每个函数都有配置好的 binding。最后按 {@code mars.rocketmq.topology} 核验或创建主题与消费组。
 *
 * @since 2026-09-22
 */
public final class RocketMqConventionVerifier implements InitializingBean {

    static final String NAME_SERVER_PROPERTY = "spring.cloud.stream.rocketmq.binder.name-server";
    static final String FUNCTION_DEFINITION_PROPERTY = "spring.cloud.function.definition";
    static final String APPLICATION_NAME_PROPERTY = "spring.application.name";

    private final Environment environment;
    private final MarsRocketMqProperties properties;
    private final RocketMQBinderConfigurationProperties binder;
    private final RocketMqBindingCatalog catalog;
    private final List<TransactionStateChecker> checkers;
    private final RocketMqTopologyManager topology;

    public RocketMqConventionVerifier(Environment environment,
                                      MarsRocketMqProperties properties,
                                      RocketMQBinderConfigurationProperties binder,
                                      RocketMqBindingCatalog catalog,
                                      List<TransactionStateChecker> checkers,
                                      RocketMqTopologyManager topology) {
        this.environment = environment;
        this.properties = properties;
        this.binder = binder;
        this.catalog = catalog;
        this.checkers = checkers;
        this.topology = topology;
    }

    @Override
    public void afterPropertiesSet() {
        verifyBinder();
        String prefix = MessagingNames.requirePrefix(properties.getPrefix());
        String application = environment.getProperty(APPLICATION_NAME_PROPERTY, "");
        if (!catalog.consumers().isEmpty()) {
            require(MessagingNames.isName(application),
                    "有消费 binding 时 spring.application.name 必须是小写连字符形态的应用名，收到: " + application);
        }
        for (RocketMqBinding binding : catalog.bindings()) {
            verifyBinding(binding, prefix, application);
        }
        verifyFunctionDefinition();
        if (properties.getTopology() != RocketMqTopologyMode.OFF) {
            topology.apply(properties.getTopology(), catalog, properties.getTopicQueues());
        }
    }

    private void verifyBinder() {
        require(environment.containsProperty(NAME_SERVER_PROPERTY) && binder.getNameServer() != null
                        && !binder.getNameServer().isBlank(),
                "RocketMQ 名字服务器必须显式配置：设置环境变量 ROCKETMQ_NAME_SERVER（host:port），"
                        + "不允许回退到 binder 的内置默认地址");
        require(!binder.getEnableMsgTrace(),
                "spring.cloud.stream.rocketmq.binder.enable-msg-trace 必须关闭：消息轨迹主题不在本项目的可观测性设计里");
    }

    private void verifyBinding(RocketMqBinding binding, String prefix, String application) {
        String name = binding.name();
        Matcher matcher = RocketMqBindingCatalog.BINDING_NAME.matcher(name);
        require(matcher.matches(), "binding [" + name + "] 的名字必须形如 <函数名>-in-<n> 或 <函数名>-out-<n>");
        String rawTopic = binding.rawTopic();
        require(rawTopic != null && !rawTopic.isBlank(), "binding [" + name + "] 必须显式配置 destination（主题名）");
        require(prefix.isEmpty() || !rawTopic.startsWith(prefix),
                "binding [" + name + "] 的 destination 不得自带运行环境前缀 " + prefix + "，前缀由 starter 按 MARS_MQ_PREFIX 加上: " + rawTopic);
        require(MessagingNames.isTopic(rawTopic),
                "binding [" + name + "] 的 destination 必须形如 <domain>-event（小写字母、数字与单个连字符），收到: " + rawTopic);
        require(!binding.messageTrace(),
                "binding [" + name + "] 的 enable-msg-trace 必须关闭：消息轨迹主题不在本项目的可观测性设计里");
        if (binding.kind() == RocketMqBinding.Kind.CONSUMER) {
            verifyConsumer(binding, prefix, application);
        } else {
            verifyProducer(binding);
        }
    }

    private void verifyConsumer(RocketMqBinding binding, String prefix, String application) {
        String name = binding.name();
        String rawGroup = binding.rawGroup();
        require(rawGroup != null && !rawGroup.isBlank(), "消费 binding [" + name + "] 必须显式配置 group（消费组名），不接受匿名消费组");
        require(prefix.isEmpty() || !rawGroup.startsWith(prefix),
                "消费 binding [" + name + "] 的 group 不得自带运行环境前缀 " + prefix + ": " + rawGroup);
        String expected = MessagingNames.consumerGroup(application, binding.rawTopic());
        require(expected.equals(rawGroup),
                "消费 binding [" + name + "] 的 group 必须是 <应用名>-<主题> 即 " + expected + "，收到: " + rawGroup);
        require(binding.maxAttempts() == 1,
                "消费 binding [" + name + "] 的 consumer.max-attempts 必须为 1：进程内重试关闭，失败交给 RocketMQ 按 maxReconsumeTimes 重投");
        require(!binding.batchMode(), "消费 binding [" + name + "] 不得开启 batch-mode：一个事件一个函数调用");
    }

    private void verifyProducer(RocketMqBinding binding) {
        String name = binding.name();
        String type = binding.producerType();
        require("Normal".equalsIgnoreCase(type) || "Trans".equalsIgnoreCase(type),
                "生产 binding [" + name + "] 的 producer.producer-type 只能是 Normal 或 Trans，收到: " + type);
        if (binding.transactional()) {
            require(MarsTransactionListener.BEAN_NAME.equals(binding.transactionListener()),
                    "事务生产 binding [" + name + "] 的 producer.transaction-listener 必须是 " + MarsTransactionListener.BEAN_NAME);
            List<String> supporting = new ArrayList<>();
            for (TransactionStateChecker checker : checkers) {
                if (checker.supports(binding.rawTopic())) {
                    supporting.add(checker.getClass().getName());
                }
            }
            require(supporting.size() == 1,
                    "事务生产 binding [" + name + "] 的主题 " + binding.rawTopic() + " 必须有且只有一个 TransactionStateChecker 支持，当前: " + supporting);
        } else {
            require(binding.transactionListener() == null || binding.transactionListener().isBlank(),
                    "普通生产 binding [" + name + "] 不得配置 producer.transaction-listener");
        }
    }

    private void verifyFunctionDefinition() {
        String definition = environment.getProperty(FUNCTION_DEFINITION_PROPERTY, "");
        if (definition.isBlank()) {
            return;
        }
        for (String function : Arrays.stream(definition.split("[;|,]")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            boolean configured = catalog.bindings().stream().anyMatch(binding -> binding.name().startsWith(function + "-"));
            require(configured, "函数 [" + function + "] 在 spring.cloud.function.definition 里声明，但没有配置任何 binding（"
                    + function + "-in-0 或 " + function + "-out-0）");
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
