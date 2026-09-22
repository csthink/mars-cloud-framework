package com.mars.cloud.rocketmq;

import com.alibaba.cloud.stream.binder.rocketmq.autoconfigurate.ExtendedBindingHandlerMappingsProviderConfiguration;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.rocketmq.autoconfigure.MarsRocketMqAutoConfiguration;
import com.mars.cloud.rocketmq.autoconfigure.MarsRocketMqDefaultsEnvironmentPostProcessor;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.integration.autoconfigure.IntegrationAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.function.cloudevent.CloudEventsFunctionExtensionConfiguration;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.function.FunctionConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用 Spring Cloud Stream 的 test binder 装起一个带本 starter 的上下文，不起 RocketMQ。
 */
final class StreamTestSupport {

    static final String APPLICATION = "mars-cloud-order-service";
    /** 消费的主题。test binder 会把同名的生产与消费 binding 直接接在一起，所以生产 binding 用另一个主题。 */
    static final String TOPIC = "order-event";
    static final String GROUP = APPLICATION + "-" + TOPIC;
    static final String OUT_TOPIC = "payment-event";

    private StreamTestSupport() {
    }

    /** 基础运行器：名字服务器占位、主题核验关闭、应用名、EnvironmentPostProcessor 的默认值已注入。 */
    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // 测试不吃开发机上的环境变量（如 MARS_MQ_PREFIX）：把系统环境变量源换成空的，环境变量映射由专门的测试覆盖
                    context.getEnvironment().getPropertySources().replace(
                            org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new org.springframework.core.env.MapPropertySource(
                                    org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, java.util.Map.of()));
                    new MarsRocketMqDefaultsEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null);
                })
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        IntegrationAutoConfiguration.class,
                        ContextFunctionCatalogAutoConfiguration.class,
                        CloudEventsFunctionExtensionConfiguration.class,
                        BindingServiceConfiguration.class,
                        FunctionConfiguration.class,
                        ExtendedBindingHandlerMappingsProviderConfiguration.class,
                        MarsRocketMqAutoConfiguration.class))
                .withUserConfiguration(TestChannelBinderConfiguration.class)
                .withPropertyValues(
                        "spring.application.name=" + APPLICATION,
                        "spring.cloud.stream.rocketmq.binder.name-server=127.0.0.1:1",
                        "mars.rocketmq.topology=off");
    }

    /** 一个消费函数、一个事务生产 binding、一个普通生产 binding 的典型配置。 */
    static String[] typicalBindings() {
        return new String[] {
                "spring.cloud.function.definition=orderPaid",
                "spring.cloud.stream.bindings.orderPaid-in-0.destination=" + TOPIC,
                "spring.cloud.stream.bindings.orderPaid-in-0.group=" + GROUP,
                "spring.cloud.stream.output-bindings=paymentTx;paymentPlain;orderLoop",
                "spring.cloud.stream.bindings.paymentTx-out-0.destination=" + OUT_TOPIC,
                "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.group=" + APPLICATION + "-payment-tx",
                "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.producer-type=Trans",
                "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.transaction-listener=marsTransactionListener",
                "spring.cloud.stream.bindings.paymentPlain-out-0.destination=" + OUT_TOPIC,
                "spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group=" + APPLICATION + "-payment-plain",
                "spring.cloud.stream.bindings.orderLoop-out-0.destination=" + TOPIC,
                "spring.cloud.stream.rocketmq.bindings.orderLoop-out-0.producer.group=" + APPLICATION + "-order-loop"
        };
    }

    /** 只记录提交与回滚次数的事务管理器，供需要真实事务同步的测试使用。 */
    static final class RecordingTransactionManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    /** 收到的消息与当时的线程上下文。 */
    record Received(Message<EventEnvelope<Map<String, Object>>> message, String callerSubject, String traceId) {
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderApplication {

        final List<Received> received = new CopyOnWriteArrayList<>();

        @Bean
        Consumer<Message<EventEnvelope<Map<String, Object>>>> orderPaid(
                org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer) {
            return message -> {
                io.micrometer.tracing.Tracer t = tracer.getIfAvailable();
                String traceId = t == null || t.currentSpan() == null ? null : t.currentSpan().context().traceId();
                received.add(new Received(message,
                        com.mars.cloud.common.context.CallerContextHolder.current()
                                .map(com.mars.cloud.common.context.CallerContext::subject).orElse(null),
                        traceId));
                if ("fail".equals(message.getPayload().key())) {
                    throw new IllegalStateException("消费失败样本");
                }
            };
        }

        @Bean
        TransactionStateChecker orderEventChecker() {
            return new TransactionStateChecker() {
                @Override
                public boolean supports(String topic) {
                    return OUT_TOPIC.equals(topic);
                }

                @Override
                public LocalTransactionState check(EventEnvelope<?> envelope, MessageExt raw) {
                    return LocalTransactionState.COMMIT_MESSAGE;
                }
            };
        }
    }
}
