package com.mars.cloud.rocketmq;

import com.alibaba.cloud.stream.binder.rocketmq.autoconfigurate.ExtendedBindingHandlerMappingsProviderConfiguration;
import com.alibaba.cloud.stream.binder.rocketmq.integration.outbound.RocketMQProduceFactory;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import com.mars.cloud.rocketmq.autoconfigure.*;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.integration.autoconfigure.IntegrationAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.function.cloudevent.CloudEventsFunctionExtensionConfiguration;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.function.FunctionConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.mockito.MockedStatic;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 实际 Stream 生命周期，只替换网络 producer；保留默认绑定重试设置。 */
class TransactionBindingLifecycleTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(c -> {
                    c.getBeanFactory().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
                    new MarsRocketMqDefaultsEnvironmentPostProcessor().postProcessEnvironment(c.getEnvironment(), null);
                })
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, IntegrationAutoConfiguration.class,
                        ContextFunctionCatalogAutoConfiguration.class, CloudEventsFunctionExtensionConfiguration.class,
                        BindingServiceConfiguration.class, FunctionConfiguration.class,
                        ExtendedBindingHandlerMappingsProviderConfiguration.class))
                .withUserConfiguration(Checks.class)
                .withPropertyValues("spring.cloud.stream.output-bindings=paymentTx-out-0",
                        "spring.cloud.stream.bindings.paymentTx-out-0.destination=payment-event",
                        "spring.cloud.stream.rocketmq.binder.name-server=127.0.0.1:1",
                        "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.group=payment-service-payment-tx",
                        "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.producer-type=Trans",
                        "spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.transaction-listener=marsTransactionListener");
    }

    @Test
    void listenerInstalledBeforeFirstStartWithoutSendingAndCloseReleasesProducer() throws Exception {
        var listener = mock(TransactionListener.class);
        var producer = mock(TransactionMQProducer.class);
        try (var factory = factory(producer)) {
            runner().withBean("marsTransactionListener", TransactionListener.class, () -> listener).run(c -> {
                assertThat(c).hasNotFailed();
                var order = inOrder(producer);
                order.verify(producer).setTransactionListener(listener);
                order.verify(producer).setTopics(java.util.List.of("payment-event"));
                order.verify(producer).start();
                verify(producer, never()).sendMessageInTransaction(any(), any());
                assertThat(c.getBean(BindingService.class).getProducerBinding("paymentTx-out-0").isRunning()).isTrue();
            });
            verify(producer).shutdown();
        }
    }

    @Test
    void namedBinderWithOwnEnvironmentUsesEachApplicationsListener() throws Exception {
        var first = mock(TransactionMQProducer.class);
        var second = mock(TransactionMQProducer.class);
        var one = mock(TransactionListener.class);
        var two = mock(TransactionListener.class);
        try (var factory = mockStatic(RocketMQProduceFactory.class)) {
            factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(first, second);
            var named = runner().withPropertyValues("spring.cloud.stream.default-binder=local",
                    "spring.cloud.stream.binders.local.type=mars-rocketmq",
                    "spring.cloud.stream.binders.local.environment.spring.main.banner-mode=off");
            named.withBean("marsTransactionListener", TransactionListener.class, () -> one).run(a -> {
                assertThat(a).hasNotFailed();
                named.withBean("marsTransactionListener", TransactionListener.class, () -> two).run(b -> {
                    assertThat(b).hasNotFailed();
                    verify(first).setTransactionListener(one);
                    verify(second).setTransactionListener(two);
                    verify(first, never()).setTransactionListener(two);
                    verify(second, never()).setTransactionListener(one);
                });
            });
            verify(first).shutdown();
            verify(second).shutdown();
        }
    }

    @Test
    void missingListenerFailsContextEvenWithDefaultRetry() {
        try (var factory = factory(mock(TransactionMQProducer.class))) {
            runner().run(c -> assertThat(c).hasFailed());
            factory.verifyNoInteractions();
        }
    }

    @Test
    void wrongListenerTypeFailsContext() {
        try (var factory = factory(mock(TransactionMQProducer.class))) {
            runner().withBean("marsTransactionListener", String.class, () -> "wrong").run(c -> assertThat(c).hasFailed());
            factory.verifyNoInteractions();
        }
    }

    @Test
    void producerStartFailureFailsContextAndReleasesAllocatedProducer() throws Exception {
        var producer = mock(TransactionMQProducer.class);
        doThrow(new MQClientException("start rejected", null)).when(producer).start();
        try (var factory = factory(producer)) {
            withListener(runner()).run(c -> assertThat(c).hasFailed());
            verify(producer).shutdown();
        }
    }

    @Test
    void unboundTransactionalOutputCannotReportReady() {
        withListener(runner().withPropertyValues("spring.cloud.stream.output-bindings=")).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void disabledAutoStartupFailsAndReleasesUnstartedProducer() {
        var producer = mock(TransactionMQProducer.class);
        try (var factory = factory(producer)) {
            withListener(runner().withPropertyValues("spring.cloud.stream.bindings.paymentTx-out-0.producer.auto-startup=false"))
                    .run(c -> assertThat(c).hasFailed());
            verify(producer).shutdown();
        }
    }

    @Test
    void secondBindingFailureReleasesFirstProducer() throws Exception {
        var first = mock(TransactionMQProducer.class);
        var second = mock(TransactionMQProducer.class);
        doThrow(new MQClientException("second rejected", null)).when(second).start();
        try (var factory = mockStatic(RocketMQProduceFactory.class)) {
            factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(first, second);
            withListener(runner().withPropertyValues("spring.cloud.stream.output-bindings=paymentTx-out-0;otherTx-out-0",
                    "spring.cloud.stream.bindings.otherTx-out-0.destination=other-event",
                    "spring.cloud.stream.rocketmq.bindings.otherTx-out-0.producer.group=payment-service-other-tx",
                    "spring.cloud.stream.rocketmq.bindings.otherTx-out-0.producer.producer-type=Trans",
                    "spring.cloud.stream.rocketmq.bindings.otherTx-out-0.producer.transaction-listener=marsTransactionListener"))
                    .run(c -> assertThat(c).hasFailed());
            verify(first).shutdown();
            verify(second).shutdown();
        }
    }

    @Test
    void originalBinderAndMisleadingTestAliasAreRejectedBeforeStarting() {
        for (String[] settings : new String[][] {
                {"spring.cloud.stream.default-binder=rocketmq"},
                {"spring.cloud.stream.default-binder=integration", "spring.cloud.stream.binders.integration.type=rocketmq"},
                {"spring.cloud.stream.bindings.paymentTx-out-0.binder=legacy", "spring.cloud.stream.binders.legacy.type=rocketmq"}}) {
            try (var factory = factory(mock(TransactionMQProducer.class))) {
                withListener(runner().withPropertyValues(settings)).run(c -> {
                    assertThat(c).hasFailed();
                    assertThat(c.getStartupFailure()).hasRootCauseMessage(
                            "事务 binding [paymentTx-out-0] 必须使用 mars-rocketmq binder，收到: rocketmq");
                });
                factory.verifyNoInteractions();
            }
        }
    }

    @Test
    void bindingCanRestartWithANewProducerAndTheSameContextListener() throws Exception {
        var first = mock(TransactionMQProducer.class);
        var next = mock(TransactionMQProducer.class);
        var listener = mock(TransactionListener.class);
        try (var factory = mockStatic(RocketMQProduceFactory.class)) {
            factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(first, next);
            runner().withBean("marsTransactionListener", TransactionListener.class, () -> listener).run(c -> {
                assertThat(c).hasNotFailed();
                var binding = c.getBean(BindingService.class).getProducerBinding("paymentTx-out-0");
                binding.stop();
                assertThat(binding.isRunning()).isFalse();
                binding.start();
                assertThat(binding.isRunning()).isTrue();
                verify(next).setTransactionListener(listener);
            });
            verify(first).shutdown();
            verify(next).shutdown();
        }
    }

    @Test
    void namedEnvironmentCannotReplaceTransactionProducerWithNormalProducer() {
        var normal = mock(org.apache.rocketmq.client.producer.DefaultMQProducer.class);
        try (var factory = mockStatic(RocketMQProduceFactory.class)) {
            factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(normal);
            withListener(runner().withPropertyValues("spring.cloud.stream.default-binder=local",
                    "spring.cloud.stream.binders.local.type=mars-rocketmq",
                    "spring.cloud.stream.binders.local.environment.spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.producer-type=Normal"))
                    .run(c -> {
                        assertThat(c).hasFailed();
                        assertThat(c.getStartupFailure()).hasRootCauseMessage(
                                "事务 binding [paymentTx-out-0] 的实际事务 producer 类型、组或监听器与应用配置不一致，或尚未启动");
                    });
            verify(normal).shutdown();
        }
    }

    private ApplicationContextRunner withListener(ApplicationContextRunner base) {
        return base.withBean("marsTransactionListener", TransactionListener.class, () -> mock(TransactionListener.class));
    }

    private MockedStatic<RocketMQProduceFactory> factory(TransactionMQProducer producer) {
        var factory = mockStatic(RocketMQProduceFactory.class);
        factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(producer);
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RocketMQExtendedBindingProperties.class)
    static class Checks {
        @Bean
        RocketMqBindingCatalog catalog(BindingServiceProperties properties, RocketMQExtendedBindingProperties extended, Environment environment) {
            return new RocketMqBindingCatalog(properties, extended, new BindingPrefixApplier(environment));
        }
        @Bean
        TransactionBindingLifecycle transactionBindingLifecycle(RocketMqBindingCatalog catalog, BindingServiceProperties properties, BindingService service, org.springframework.cloud.stream.binder.BinderFactory factory) {
            return new TransactionBindingLifecycle(catalog, properties, service, factory);
        }
    }
}
