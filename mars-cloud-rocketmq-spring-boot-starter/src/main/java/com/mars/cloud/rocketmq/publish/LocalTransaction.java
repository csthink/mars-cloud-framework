package com.mars.cloud.rocketmq.publish;

/**
 * 与一条事务消息绑定的本地事务。
 *
 * <p>由 {@link TransactionalEventPublisher} 在半消息发送成功后于调用线程执行：正常返回即提交消息，
 * 抛出任何 Throwable 即回滚消息并把它包成 {@link com.mars.cloud.rocketmq.MessagePublishException} 抛给调用方：
 * 致命的 Error 也会以这个 RuntimeException 的 cause 出现，统一捕获 MessagePublishException 的调用方要检查 cause。
 * 实现通常是一次 {@code TransactionTemplate} 或 {@code @Transactional} 服务方法调用。
 *
 * @since 2026-09-22
 */
@FunctionalInterface
public interface LocalTransaction {

    void execute();
}
