package com.mars.cloud.rocketmq.contract;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.common.messaging.EventEnvelope;
import com.mars.cloud.common.messaging.MessagingHeaders;
import com.mars.cloud.rocketmq.DelayLevel;
import com.mars.cloud.rocketmq.MessagePublishException;
import com.mars.cloud.rocketmq.RocketMqHeaders;
import com.mars.cloud.rocketmq.autoconfigure.MarsRocketMqProperties;
import com.mars.cloud.rocketmq.publish.PlainEventPublisher;
import com.mars.cloud.rocketmq.publish.TransactionalEventPublisher;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 对真实 RocketMQ 的契约测试。名字服务器来自环境变量 ROCKETMQ_NAME_SERVER，前缀来自 MARS_MQ_PREFIX。
 */
@SpringBootTest(classes = ContractApplication.class)
@ActiveProfiles("contract")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RocketMqContractTest {

    static final String APPLICATION = "mars-cloud-contract-service";
    static final String GROUP = APPLICATION + "-contract-event";
    static final Path CLIENT_LOG = Path.of(System.getProperty("user.home"), "logs", "rocketmqlogs", "rocketmq_client.log");
    static long clientLogSizeAtStart;
    static String prefix;

    @Autowired
    TransactionalEventPublisher transactional;
    @Autowired
    PlainEventPublisher plain;
    @Autowired
    Tracer tracer;
    @Autowired
    MarsRocketMqProperties properties;
    @Autowired
    tools.jackson.databind.ObjectMapper mapper;

    @BeforeAll
    static void recordClientLogSize() throws IOException {
        clientLogSizeAtStart = Files.exists(CLIENT_LOG) ? Files.size(CLIENT_LOG) : -1;
        prefix = System.getenv().getOrDefault("MARS_MQ_PREFIX", "");
    }

    @AfterAll
    static void deleteContractTopology() throws Exception {
        String nameServer = System.getenv("ROCKETMQ_NAME_SERVER");
        if (nameServer == null) {
            return;
        }
        String group = prefix + GROUP;
        ContractTopology.delete(nameServer,
                Set.of(prefix + "contract-event", "%RETRY%" + group, "%DLQ%" + group), Set.of(group));
    }

    private static EventEnvelope<Map<String, Object>> event(String key) {
        return EventEnvelope.of("PAID", APPLICATION, key, Map.of("marker", UUID.randomUUID().toString()));
    }

    private static List<ContractApplication.Delivery> deliveriesOf(String key) {
        return ContractApplication.DELIVERIES.stream().filter(d -> d.envelope().key().equals(key)).toList();
    }

    @Test
    @Order(1)
    void committedTransactionIsVisibleAndCarriesContext() {
        String key = "commit-" + UUID.randomUUID();
        AtomicReference<Thread> ran = new AtomicReference<>();
        Span parent = tracer.nextSpan().name("contract-commit").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent);
             CallerContextHolder.Scope caller = CallerContextHolder.open(new CallerContext("user-42", "portal", "default"))) {
            transactional.publish("contractTx-out-0", event(key), () -> ran.set(Thread.currentThread()));
        } finally {
            parent.end();
        }
        assertThat(ran.get()).as("本地事务在调用线程执行").isSameAs(Thread.currentThread());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveriesOf(key)).hasSize(1));
        ContractApplication.Delivery delivery = deliveriesOf(key).get(0);
        assertThat(delivery.envelope().eventType()).isEqualTo("PAID");
        assertThat(delivery.envelope().traceId()).isEqualTo(parent.context().traceId());
        assertThat(delivery.traceId()).as("消费侧 span 与生产侧同一 trace").isEqualTo(parent.context().traceId());
        assertThat(delivery.callerSubject()).isEqualTo("user-42");
        assertThat(delivery.headers()).containsEntry(RocketMqHeaders.RECEIVED_TAGS, "PAID")
                .containsEntry(RocketMqHeaders.RECEIVED_KEYS, key)
                .containsEntry(MessagingHeaders.EVENT_ID, delivery.envelope().eventId())
                .containsEntry(InternalCallHeaders.SUBJECT, "user-42")
                .containsKey(MessagingHeaders.TRACEPARENT)
                .containsEntry(RocketMqHeaders.RECEIVED_TOPIC, prefix + "contract-event");
        assertThat(ContractApplication.BUSINESS_EXECUTIONS).containsEntry(delivery.envelope().eventId(), 1);
    }

    @Test
    @Order(2)
    void rolledBackTransactionIsNeverVisible() {
        String key = "rollback-" + UUID.randomUUID();
        assertThatThrownBy(() -> transactional.publish("contractTx-out-0", event(key), () -> {
            throw new IllegalStateException("本地事务失败样本");
        })).isInstanceOf(MessagePublishException.class).hasMessageContaining("消息已回滚")
                .hasCauseInstanceOf(IllegalStateException.class);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(deliveriesOf(key)).isEmpty());
    }

    @Test
    @Order(3)
    void duplicateDeliveryOfTheSameEventIsProcessedOnce() {
        EventEnvelope<Map<String, Object>> envelope = event("dup-" + UUID.randomUUID());
        plain.publish("contractPlain-out-0", envelope);
        plain.publish("contractPlain-out-0", envelope);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveriesOf(envelope.key())).hasSize(2));
        assertThat(ContractApplication.BUSINESS_EXECUTIONS).containsEntry(envelope.eventId(), 1);
    }

    @Test
    @Order(4)
    void plainDelayLevelIsHonored() {
        String key = "delay-" + UUID.randomUUID();
        long sentAt = System.currentTimeMillis();
        plain.publish("contractPlain-out-0", event(key), DelayLevel.LEVEL_2);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deliveriesOf(key)).hasSize(1));
        assertThat(deliveriesOf(key).get(0).at() - sentAt).isGreaterThanOrEqualTo(4500);
    }

    @Test
    @Order(5)
    void consumerFailureIsRedeliveredThenDeadLettered() throws Exception {
        String key = "fail-" + UUID.randomUUID();
        String dlq = "%DLQ%" + prefix + GROUP;
        long before = dlqMessages(dlq);
        plain.publish("contractPlain-out-0", event(key));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(deliveriesOf(key)).hasSize(3));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(dlqMessages(dlq)).isEqualTo(before + 1));
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(deliveriesOf(key)).as("超过 maxReconsumeTimes 后不再投递").hasSize(3));
    }

    @Test
    @Order(6)
    void localTransactionErrorRollsBackTheMessage() {
        String key = "crash-error-" + UUID.randomUUID();
        assertThatThrownBy(() -> transactional.publish("contractTx-out-0", event(key), () -> {
            throw new AssertionError("本地事务里的 Error 样本");
        })).isInstanceOf(MessagePublishException.class).hasMessageContaining("消息已回滚").hasCauseInstanceOf(AssertionError.class);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(deliveriesOf(key)).isEmpty());
    }

    @Test
    @Order(7)
    void crashBeforeAnsweringBrokerIsResolvedByCheckBack() throws Exception {
        String key = "crash-commit-" + UUID.randomUUID();
        // 用同一生产者组的另一个客户端发半消息并答 UNKNOW，随后关闭它：等同于该进程在答复 broker 前崩溃，
        // broker 回查时组里只剩本应用的生产者，回查落到 starter 的监听器与检查器
        EventEnvelope<Map<String, Object>> envelope = event(key);
        TransactionMQProducer crashed = new TransactionMQProducer(prefix + APPLICATION + "-tx");
        crashed.setNamesrvAddr(System.getenv("ROCKETMQ_NAME_SERVER"));
        crashed.setInstanceName("contract-crash-" + UUID.randomUUID());
        crashed.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(org.apache.rocketmq.common.message.Message msg, Object arg) {
                return LocalTransactionState.UNKNOW;
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                throw new IllegalStateException("崩溃的进程不该再收到回查");
            }
        });
        crashed.start();
        try {
            org.apache.rocketmq.common.message.Message half = new org.apache.rocketmq.common.message.Message(
                    prefix + "contract-event", "PAID", key, mapper.writeValueAsBytes(envelope));
            half.putUserProperty(MessagingHeaders.EVENT_ID, envelope.eventId());
            crashed.sendMessageInTransaction(half, null);
        } finally {
            crashed.shutdown();
        }
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(ContractApplication.CHECKED).contains(key);
            assertThat(deliveriesOf(key)).hasSize(1);
        });
        assertThat(deliveriesOf(key).get(0).headers()).containsKey(RocketMqHeaders.TRANSACTION_CHECK_TIMES);
        assertThat(deliveriesOf(key).get(0).envelope().eventId()).isEqualTo(envelope.eventId());
    }

    @Test
    @Order(8)
    void verifyModeFailsForMissingTopicWithTheCreateCommand() {
        String nameServer = System.getenv("ROCKETMQ_NAME_SERVER");
        new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                        org.springframework.boot.integration.autoconfigure.IntegrationAutoConfiguration.class,
                        org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration.class,
                        org.springframework.cloud.function.cloudevent.CloudEventsFunctionExtensionConfiguration.class,
                        org.springframework.cloud.stream.config.BindingServiceConfiguration.class,
                        org.springframework.cloud.stream.function.FunctionConfiguration.class,
                        com.alibaba.cloud.stream.binder.rocketmq.autoconfigurate.ExtendedBindingHandlerMappingsProviderConfiguration.class,
                        com.mars.cloud.rocketmq.autoconfigure.MarsRocketMqAutoConfiguration.class))
                .withPropertyValues(
                        "spring.application.name=" + APPLICATION,
                        "spring.cloud.stream.rocketmq.binder.name-server=" + nameServer,
                        "mars.rocketmq.prefix=" + prefix,
                        "mars.rocketmq.topology=verify",
                        "spring.cloud.stream.default.consumer.max-attempts=1",
                        "spring.cloud.stream.rocketmq.binder.enable-msg-trace=false",
                        "spring.cloud.stream.rocketmq.default.producer.enable-msg-trace=false",
                        "spring.cloud.stream.output-bindings=missing",
                        "spring.cloud.stream.bindings.missing-out-0.destination=missing-event",
                        "spring.cloud.stream.rocketmq.bindings.missing-out-0.producer.group=" + prefix + APPLICATION + "-missing")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("mqadmin updateTopic -c ")
                            .hasStackTraceContaining(" -t " + prefix + "missing-event -r 4 -w 4");
                });
    }

    @Test
    @Order(9)
    void clientLogsGoToStdoutNotToTheHomeDirectory() throws IOException {
        long now = Files.exists(CLIENT_LOG) ? Files.size(CLIENT_LOG) : -1;
        assertThat(now).as("RocketMQ 客户端日志文件不得增长").isEqualTo(clientLogSizeAtStart);
    }

    private long dlqMessages(String topic) throws Exception {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(System.getenv("ROCKETMQ_NAME_SERVER"));
        admin.setInstanceName("contract-dlq-" + UUID.randomUUID());
        admin.start();
        try {
            TopicStatsTable stats = admin.examineTopicStats(topic);
            return stats.getOffsetTable().values().stream().mapToLong(o -> o.getMaxOffset() - o.getMinOffset()).sum();
        } catch (org.apache.rocketmq.client.exception.MQClientException e) {
            return 0;
        } finally {
            admin.shutdown();
        }
    }
}
