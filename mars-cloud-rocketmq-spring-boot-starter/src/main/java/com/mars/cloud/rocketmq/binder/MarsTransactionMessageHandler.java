/*
 * Copyright 2013-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mars.cloud.rocketmq.binder;

import com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst;
import com.alibaba.cloud.stream.binder.rocketmq.integration.outbound.RocketMQProduceFactory;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQProducerProperties;
import com.alibaba.cloud.stream.binder.rocketmq.support.RocketMQMessageConverterSupport;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.Lifecycle;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.support.ErrorMessageStrategy;
import org.springframework.integration.support.ErrorMessageUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;

/**
 * 事务输出的生命周期及发送适配，衍生自 SCA 2025.1.0.0 RocketMQProducerMessageHandler。
 * 保留 Apache-2.0 许可，复用上游 producer factory、转换器与错误消息约定。
 * 监听器从当前 BeanFactory 解析，在第一次启动前安装；发送过程不重新安装。
 */
public final class MarsTransactionMessageHandler extends AbstractMessageHandler implements Lifecycle, DisposableBean {
    private final String destination;
    private final RocketMQProducerProperties properties;
    private final MessageChannel errorChannel;
    private final ErrorMessageStrategy errorStrategy;
    private TransactionMQProducer producer;
    private volatile boolean running;

    public MarsTransactionMessageHandler(String destination, RocketMQProducerProperties properties,
            MessageChannel errorChannel, ErrorMessageStrategy errorStrategy) {
        this.destination = destination;
        this.properties = properties;
        this.errorChannel = errorChannel;
        this.errorStrategy = errorStrategy;
    }

    @Override
    protected synchronized void onInit() {
        super.onInit();
        if (producer != null) {
            throw new IllegalStateException("事务 handler 不允许重复初始化");
        }
        initializeProducer();
    }

    private void initializeProducer() {
        TransactionListener listener = getBeanFactory().getBean(properties.getTransactionListener(), TransactionListener.class);
        producer = (TransactionMQProducer) RocketMQProduceFactory.initRocketMQProducer(destination, properties);
        producer.setTransactionListener(listener);
        // 让 client 在 start 的路由加载与心跳阶段登记该主题，零发送的重启实例才能收到 broker 回查。
        producer.setTopics(java.util.List.of(destination));
    }

    @Override
    public synchronized void start() {
        if (running) return;
        if (producer == null) initializeProducer();
        try {
            producer.start();
            running = true;
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("事务 producer 启动失败: " + destination, failure);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (producer != null) {
            producer.shutdown();
            producer = null;
        }
    }

    boolean matches(String group, String listener) {
        return group.equals(properties.getGroup()) && listener.equals(properties.getTransactionListener());
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public void destroy() { stop(); }

    @Override
    protected void handleMessageInternal(Message<?> message) {
        try {
            if (!running) throw new IllegalStateException("事务 producer 未启动");
            var converted = RocketMQMessageConverterSupport.convertMessage2MQ(destination, message);
            SendResult result = producer.sendMessageInTransaction(converted,
                    message.getHeaders().get(RocketMQConst.USER_TRANSACTIONAL_ARGS));
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new MessagingException("message send fail.SendStatus is not OK.");
            }
        } catch (Exception failure) {
            if (errorChannel != null) {
                errorChannel.send(errorStrategy.buildErrorMessage(failure,
                        ErrorMessageUtils.getAttributeAccessor(message, message)));
            } else {
                throw new MessagingException(message, failure);
            }
        }
    }
}
