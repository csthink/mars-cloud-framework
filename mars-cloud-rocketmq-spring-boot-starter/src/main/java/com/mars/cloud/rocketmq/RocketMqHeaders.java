package com.mars.cloud.rocketmq;

/**
 * RocketMQ binder 特有的消息头名。
 *
 * <p>生产侧的头由 binder 的消息转换器读取并转成 RocketMQ 的 tag、key 与延迟档；消费侧的头由 binder 从
 * 收到的消息填入。与中间件无关的头名在 common 的 {@link com.mars.cloud.common.messaging.MessagingHeaders}。
 * 业务代码经发布器与消费助手使用这些头，不直接写字符串。
 *
 * @since 2026-09-22
 */
public final class RocketMqHeaders {

    /** 生产侧：消息 tag，值为事件名。 */
    public static final String TAGS = "TAGS";

    /** 生产侧：消息 key，值为业务键。 */
    public static final String KEYS = "KEYS";

    /** 生产侧：固定档延迟，值为 1 到 18 的档位；事务消息忽略它。 */
    public static final String DELAY = "DELAY";

    /** 消费侧：消息 tag。 */
    public static final String RECEIVED_TAGS = "ROCKET_TAGS";

    /** 消费侧：消息 key。 */
    public static final String RECEIVED_KEYS = "ROCKET_KEYS";

    /** 消费侧：RocketMQ 消息标识。 */
    public static final String RECEIVED_MESSAGE_ID = "ROCKET_MQ_MESSAGE_ID";

    /** 消费侧：实际主题名（带运行环境前缀）。 */
    public static final String RECEIVED_TOPIC = "ROCKET_MQ_TOPIC";

    /** 消费侧：事务消息被 broker 回查的次数，只在经过回查的消息上出现。 */
    public static final String TRANSACTION_CHECK_TIMES = "TRANSACTION_CHECK_TIMES";

    private RocketMqHeaders() {
    }
}
