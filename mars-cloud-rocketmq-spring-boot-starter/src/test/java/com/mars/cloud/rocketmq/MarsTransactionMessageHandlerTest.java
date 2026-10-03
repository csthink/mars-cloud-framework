package com.mars.cloud.rocketmq;

import com.alibaba.cloud.stream.binder.rocketmq.integration.outbound.RocketMQProduceFactory;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQProducerProperties;
import com.mars.cloud.rocketmq.binder.MarsTransactionMessageHandler;
import org.apache.rocketmq.client.producer.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.support.DefaultErrorMessageStrategy;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.ErrorMessage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MarsTransactionMessageHandlerTest {
    @Test
    void convertedTransactionPreservesPayloadTagKeyAndApplicationHeaders() throws Exception {
        var producer = mock(TransactionMQProducer.class);
        var result = new TransactionSendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(producer.sendMessageInTransaction(any(), any())).thenReturn(result);
        try (var context = context(); var factory = mockStatic(RocketMQProduceFactory.class)) {
            factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(producer);
            var handler = handler(context, null);
            try {
                handler.start();
                handler.handleMessage(MessageBuilder.withPayload("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setHeader(RocketMqHeaders.TAGS, "COMPLETED").setHeader(RocketMqHeaders.KEYS, "business-key")
                        .setHeader("X-Mars-Event-Id", "event-id").setHeader("traceparent", "trace-value").build());
                var captor = org.mockito.ArgumentCaptor.forClass(org.apache.rocketmq.common.message.Message.class);
                verify(producer).sendMessageInTransaction(captor.capture(), isNull());
                var converted = captor.getValue();
                assertThat(converted.getBody()).isEqualTo("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThat(converted.getTags()).isEqualTo("COMPLETED");
                assertThat(converted.getKeys()).isEqualTo("business-key");
                assertThat(converted.getUserProperty("X-Mars-Event-Id")).isEqualTo("event-id");
                assertThat(converted.getUserProperty("traceparent")).isEqualTo("trace-value");
                verify(producer, times(1)).setTransactionListener(context.getBean(TransactionListener.class));
            } finally { handler.destroy(); }
        }
    }

    @Test
    void failuresUseConfiguredErrorChannelOrPropagateWithFailedMessage() throws Exception {
        for (boolean channelEnabled : new boolean[] {true, false}) {
            var producer = mock(TransactionMQProducer.class);
            var failure = new IllegalStateException("intentional-send-rejection");
            when(producer.sendMessageInTransaction(any(), any())).thenThrow(failure);
            try (var context = context(); var factory = mockStatic(RocketMQProduceFactory.class)) {
                factory.when(() -> RocketMQProduceFactory.initRocketMQProducer(anyString(), any())).thenReturn(producer);
                var errors = channelEnabled ? new QueueChannel() : null;
                var handler = handler(context, errors);
                var message = MessageBuilder.withPayload(new byte[] {1}).build();
                try {
                    handler.start();
                    if (channelEnabled) {
                        handler.handleMessage(message);
                        var error = (ErrorMessage) errors.receive(1000);
                        assertThat(error).isNotNull();
                        assertThat(error.getPayload()).isSameAs(failure);
                        assertThat(error.getOriginalMessage()).isSameAs(message);
                    } else {
                        assertThatThrownBy(() -> handler.handleMessage(message)).isInstanceOf(MessagingException.class)
                                .hasCause(failure).satisfies(error -> assertThat(((MessagingException) error).getFailedMessage()).isSameAs(message));
                    }
                } finally { handler.destroy(); }
            }
        }
    }

    private GenericApplicationContext context() {
        var context = new GenericApplicationContext();
        context.registerBean("marsTransactionListener", TransactionListener.class, () -> mock(TransactionListener.class));
        context.refresh(); return context;
    }

    private MarsTransactionMessageHandler handler(GenericApplicationContext context, QueueChannel errors) {
        var properties = new RocketMQProducerProperties();
        properties.setProducerType("Trans"); properties.setGroup("test-service-tx");
        properties.setTransactionListener("marsTransactionListener");
        var handler = new MarsTransactionMessageHandler("test-event", properties, errors, new DefaultErrorMessageStrategy());
        handler.setApplicationContext(context); handler.setBeanFactory(context.getBeanFactory());
        handler.afterPropertiesSet(); return handler;
    }
}
