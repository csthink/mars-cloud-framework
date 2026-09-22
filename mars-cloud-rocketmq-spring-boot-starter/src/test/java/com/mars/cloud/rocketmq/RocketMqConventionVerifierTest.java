package com.mars.cloud.rocketmq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqConventionVerifierTest {

    private final ApplicationContextRunner runner = StreamTestSupport.runner()
            .withUserConfiguration(StreamTestSupport.OrderApplication.class)
            .withPropertyValues(StreamTestSupport.typicalBindings());

    private void assertFails(ApplicationContextRunner configured, String fragment) {
        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(fragment);
        });
    }

    @Test
    void nameServerMustBeExplicit() {
        assertFails(StreamTestSupport.runner().withUserConfiguration(StreamTestSupport.OrderApplication.class)
                        .withPropertyValues(StreamTestSupport.typicalBindings())
                        .withPropertyValues("spring.cloud.stream.rocketmq.binder.name-server="),
                "名字服务器必须显式配置");
    }

    @Test
    void messageTraceMustStayOff() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.binder.enable-msg-trace=true"), "enable-msg-trace 必须关闭");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.enable-msg-trace=true"),
                "binding [paymentPlain-out-0] 的 enable-msg-trace 必须关闭");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.orderPaid-in-0.consumer.enable-msg-trace=true"),
                "binding [orderPaid-in-0] 的 enable-msg-trace 必须关闭");
    }

    @Test
    void malformedPrefixIsRejected() {
        assertFails(runner.withPropertyValues("mars.rocketmq.prefix=S1"), "prefix 必须形如 s1-");
    }

    @Test
    void consumerNeedsAValidApplicationName() {
        assertFails(runner.withPropertyValues("spring.application.name=Order Service"), "spring.application.name 必须是小写连字符形态");
    }

    @Test
    void bindingNameMustFollowTheFunctionForm() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.orders.destination=order-event"), "必须形如 <函数名>-in-<n> 或 <函数名>-out-<n>");
    }

    @Test
    void destinationIsRequiredAndMustBeATopicName() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.paymentPlain-out-0.destination="), "必须显式配置 destination");
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.paymentPlain-out-0.destination=Payment"), "必须形如 <domain>-event");
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.paymentPlain-out-0.destination=payment"), "必须形如 <domain>-event");
    }

    @Test
    void destinationMustNotCarryThePrefixItself() {
        assertFails(runner.withPropertyValues("mars.rocketmq.prefix=s1-",
                "spring.cloud.stream.bindings.paymentPlain-out-0.destination=s1-payment-event"), "不得自带运行环境前缀");
    }

    @Test
    void consumerGroupRules() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.orderPaid-in-0.group="), "不接受匿名消费组");
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.orderPaid-in-0.group=order-consumers"), "必须是 <应用名>-<主题>");
        assertFails(runner.withPropertyValues("mars.rocketmq.prefix=s1-",
                "spring.cloud.stream.bindings.orderPaid-in-0.group=s1-" + StreamTestSupport.GROUP), "group 不得自带运行环境前缀");
    }

    @Test
    void inProcessRetryAndBatchModeAreRejected() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.orderPaid-in-0.consumer.max-attempts=3"), "max-attempts 必须为 1");
        assertFails(runner.withPropertyValues("spring.cloud.stream.bindings.orderPaid-in-0.consumer.batch-mode=true"), "不得开启 batch-mode");
    }

    @Test
    void producerTypeAndListenerRules() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.producer-type=Async"), "只能是 Normal 或 Trans");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentTx-out-0.producer.transaction-listener=other"), "必须是 marsTransactionListener");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.transaction-listener=marsTransactionListener"), "不得配置 producer.transaction-listener");
    }

    @Test
    void producerGroupRules() {
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group="), "必须显式配置 producer.group");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group=anonymous"), "必须显式配置 producer.group");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group=Payment_Producer"), "小写连字符形态");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group=payment-plain"), "必须以 <应用名>- 开头");
        assertFails(runner.withPropertyValues("spring.cloud.stream.rocketmq.bindings.paymentPlain-out-0.producer.group=" + StreamTestSupport.APPLICATION + "-payment-tx"),
                "与另一个生产 binding 重复");
    }

    @Test
    void transactionalTopicNeedsExactlyOneChecker() {
        assertFails(StreamTestSupport.runner().withUserConfiguration(StreamTestSupport.OrderApplication.class)
                        .withPropertyValues(StreamTestSupport.typicalBindings())
                        .withPropertyValues("spring.cloud.stream.bindings.paymentTx-out-0.destination=refund-event"),
                "必须有且只有一个 TransactionStateChecker");
    }

    @Test
    void declaredFunctionsNeedBindings() {
        assertFails(runner.withPropertyValues("spring.cloud.function.definition=orderPaid;ghost"), "函数 [ghost]");
        // 函数名是另一个 binding 名的前缀时也不算已配置
        assertFails(runner.withPropertyValues("spring.cloud.function.definition=orderPaid;order"), "函数 [order]");
    }
}
