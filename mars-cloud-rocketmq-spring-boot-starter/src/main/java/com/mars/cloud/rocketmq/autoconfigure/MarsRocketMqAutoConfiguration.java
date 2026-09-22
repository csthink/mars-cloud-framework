package com.mars.cloud.rocketmq.autoconfigure;

import com.alibaba.cloud.stream.binder.rocketmq.RocketMQMessageChannelBinder;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQBinderConfigurationProperties;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import com.mars.cloud.rocketmq.consume.IdempotentEventHandler;
import com.mars.cloud.rocketmq.consume.ProcessedEventStore;
import com.mars.cloud.rocketmq.internal.InboundContextInterceptor;
import com.mars.cloud.rocketmq.internal.MarsTransactionListener;
import com.mars.cloud.rocketmq.internal.MessageTracing;
import com.mars.cloud.rocketmq.internal.MicrometerMessageTracing;
import com.mars.cloud.rocketmq.publish.PlainEventPublisher;
import com.mars.cloud.rocketmq.publish.TransactionStateChecker;
import com.mars.cloud.rocketmq.publish.TransactionalEventPublisher;
import com.mars.cloud.rocketmq.topology.RocketMqTopologyManager;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * RocketMQ 消息约定的自动装配。
 *
 * <p>binder 自己的属性 bean 在 Spring Cloud Stream 的 binder 子上下文里，主上下文拿不到，所以这里在主上下文
 * 再绑一份同前缀的属性对象供约定校验与主题核验使用。
 *
 * @since 2026-09-22
 */
@AutoConfiguration
@ConditionalOnClass({RocketMQMessageChannelBinder.class, StreamBridge.class})
@EnableConfigurationProperties({MarsRocketMqProperties.class, RocketMQBinderConfigurationProperties.class,
        RocketMQExtendedBindingProperties.class})
public class MarsRocketMqAutoConfiguration {

    @Bean
    public static BindingPrefixApplier bindingPrefixApplier(Environment environment) {
        return new BindingPrefixApplier(environment);
    }

    @Bean
    public RocketMqBindingCatalog rocketMqBindingCatalog(BindingServiceProperties bindingProperties,
                                                         RocketMQExtendedBindingProperties extended,
                                                         BindingPrefixApplier applier) {
        return new RocketMqBindingCatalog(bindingProperties, extended, applier);
    }

    @Bean
    @ConditionalOnMissingBean
    public RocketMqTopologyManager rocketMqTopologyManager(RocketMQBinderConfigurationProperties binder) {
        return new RocketMqTopologyManager(binder.getNameServer());
    }

    @Bean
    public RocketMqConventionVerifier rocketMqConventionVerifier(Environment environment,
                                                                 MarsRocketMqProperties properties,
                                                                 RocketMQBinderConfigurationProperties binder,
                                                                 RocketMqBindingCatalog catalog,
                                                                 List<TransactionStateChecker> checkers,
                                                                 RocketMqTopologyManager topology) {
        return new RocketMqConventionVerifier(environment, properties, binder, catalog, checkers, topology);
    }

    @Bean(MarsTransactionListener.BEAN_NAME)
    public MarsTransactionListener marsTransactionListener(List<TransactionStateChecker> checkers,
                                                           ObjectMapper objectMapper,
                                                           MarsRocketMqProperties properties) {
        return new MarsTransactionListener(checkers, objectMapper, properties.getPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(MessageTracing.class)
    public MessageTracing noopMessageTracing() {
        return MessageTracing.NONE;
    }

    @Bean
    public TransactionalEventPublisher transactionalEventPublisher(StreamBridge streamBridge,
                                                                   RocketMqBindingCatalog catalog,
                                                                   MessageTracing tracing) {
        return new TransactionalEventPublisher(streamBridge, catalog, tracing);
    }

    @Bean
    public PlainEventPublisher plainEventPublisher(StreamBridge streamBridge,
                                                   RocketMqBindingCatalog catalog,
                                                   MessageTracing tracing) {
        return new PlainEventPublisher(streamBridge, catalog, tracing);
    }

    @Bean
    @GlobalChannelInterceptor(patterns = "*-in-*")
    public InboundContextInterceptor inboundContextInterceptor(MessageTracing tracing) {
        return new InboundContextInterceptor(tracing);
    }

    @Bean
    @ConditionalOnBean(ProcessedEventStore.class)
    @ConditionalOnMissingBean
    public IdempotentEventHandler idempotentEventHandler(ProcessedEventStore store,
                                                         ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager manager = transactionManager.getIfUnique();
        TransactionOperations operations = manager == null
                ? TransactionOperations.withoutTransaction() : new TransactionTemplate(manager);
        return new IdempotentEventHandler(store, operations);
    }

    /** 有 Micrometer Tracing 且容器里有 Tracer 与 Propagator 时传播 trace。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnBean({Tracer.class, Propagator.class})
        @ConditionalOnMissingBean(MessageTracing.class)
        public MessageTracing micrometerMessageTracing(Tracer tracer, Propagator propagator) {
            return new MicrometerMessageTracing(tracer, propagator);
        }
    }
}
