package com.mars.cloud.rocketmq.autoconfigure;

/**
 * 一个 Spring Cloud Stream binding 在本 starter 眼里的形态。
 *
 * @param name binding 名，如 {@code orderPaid-in-0}
 * @param kind 生产或消费
 * @param topic 带运行环境前缀的主题名
 * @param rawTopic 不带前缀的主题名
 * @param group 带前缀的消费组名，生产 binding 为 null
 * @param rawGroup 不带前缀的消费组名，生产 binding 为 null
 * @param producerType binder 的生产者类型，{@code Normal} 或 {@code Trans}；消费 binding 为 null
 * @param transactionListener 事务监听器 bean 名；没有配置为 null
 * @param maxAttempts Spring Cloud Stream 进程内重试次数；生产 binding 为 0
 * @param batchMode 是否批量消费；生产 binding 为 false
 * @param messageTrace binder 是否为这个 binding 开启消息轨迹
 * @since 2026-09-22
 */
public record RocketMqBinding(
        String name,
        Kind kind,
        String topic,
        String rawTopic,
        String group,
        String rawGroup,
        String producerType,
        String transactionListener,
        int maxAttempts,
        boolean batchMode,
        boolean messageTrace) {

    public enum Kind { PRODUCER, CONSUMER }

    public boolean transactional() {
        return kind == Kind.PRODUCER && "Trans".equalsIgnoreCase(producerType);
    }
}
