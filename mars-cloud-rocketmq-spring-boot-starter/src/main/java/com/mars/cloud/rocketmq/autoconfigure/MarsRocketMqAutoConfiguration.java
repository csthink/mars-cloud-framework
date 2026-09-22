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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
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

    /**
     * 幂等助手。应用提供 {@link ProcessedEventStore} bean 后装配；事务模板取自唯一的
     * {@link PlatformTransactionManager}，有多个事务管理器时必须由应用提供 {@link TransactionOperations} bean，
     * 否则启动失败，不静默退化为无事务。
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentEventHandler idempotentEventHandler(ObjectProvider<ProcessedEventStore> store,
                                                         ObjectProvider<TransactionOperations> operations,
                                                         ObjectProvider<PlatformTransactionManager> transactionManagers) {
        ProcessedEventStore processedEventStore = store.getIfAvailable();
        if (processedEventStore == null) {
            return null;
        }
        TransactionOperations explicit = operations.getIfUnique();
        if (explicit != null) {
            return new IdempotentEventHandler(processedEventStore, explicit);
        }
        List<PlatformTransactionManager> managers = transactionManagers.stream().toList();
        if (managers.size() > 1) {
            throw new IllegalStateException("存在 " + managers.size() + " 个 PlatformTransactionManager，IdempotentEventHandler 无法选择事务："
                    + "请提供唯一的 TransactionOperations bean");
        }
        TransactionOperations chosen = managers.isEmpty()
                ? TransactionOperations.withoutTransaction() : new TransactionTemplate(managers.get(0));
        return new IdempotentEventHandler(processedEventStore, chosen);
    }

    /**
     * 有 Micrometer Tracing 时在 bean 实例化期（全部 bean 定义已登记之后）再看容器里有没有 Tracer 与 Propagator，
     * 不用 {@code @ConditionalOnBean}：本自动配置按类名排在 Spring Boot 的 tracing 自动配置之前，
     * 那时 Tracer 的 bean 定义还没登记，条件会落空并让 trace 传播静默关闭。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageTracing.class)
        public MessageTracing messageTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
            Tracer t = tracer.getIfUnique();
            Propagator p = propagator.getIfUnique();
            return t == null || p == null ? MessageTracing.NONE : new MicrometerMessageTracing(t, p);
        }
    }

    /** classpath 上没有 Micrometer Tracing 时只传身份头。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    static class NoTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageTracing.class)
        public MessageTracing messageTracing() {
            return MessageTracing.NONE;
        }
    }
}
