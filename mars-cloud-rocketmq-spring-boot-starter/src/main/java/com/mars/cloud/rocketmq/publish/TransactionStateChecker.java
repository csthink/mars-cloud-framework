package com.mars.cloud.rocketmq.publish;

import com.mars.cloud.common.messaging.EventEnvelope;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * 回答 broker 对事务消息的回查：本地事务到底提交了没有。
 *
 * <p>生产者进程在本地事务提交后、答复 broker 前崩溃时，broker 会按消息回查。实现按业务表状态答复：
 * 业务写入已存在答 {@code COMMIT_MESSAGE}，确认没有写入答 {@code ROLLBACK_MESSAGE}，暂时无法判断答
 * {@code UNKNOW} 让 broker 稍后再查。每个配置为事务生产者的 binding 主题必须有且只有一个检查器 {@link #supports}。
 *
 * @since 2026-09-22
 */
public interface TransactionStateChecker {

    /**
     * 是否负责该主题。
     *
     * @param topic 不带运行环境前缀的主题名
     */
    boolean supports(String topic);

    /**
     * 按业务表状态答复回查。
     *
     * @param envelope 从消息体还原的信封，{@code payload} 为 Jackson 树节点
     * @param raw RocketMQ 原始消息
     */
    LocalTransactionState check(EventEnvelope<?> envelope, MessageExt raw);
}
