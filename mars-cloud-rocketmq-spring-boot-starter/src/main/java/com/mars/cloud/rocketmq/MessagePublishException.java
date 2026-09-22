package com.mars.cloud.rocketmq;

/**
 * 消息发送失败。
 *
 * <p>覆盖发送前校验失败、binder 或 RocketMQ 客户端异常、本地事务抛出异常三种情况；
 * 调用方按自己的错误码映射，starter 不分配错误码。
 *
 * @since 2026-09-22
 */
public class MessagePublishException extends RuntimeException {

    public MessagePublishException(String message) {
        super(message);
    }

    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
