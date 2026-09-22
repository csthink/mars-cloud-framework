package com.mars.cloud.rocketmq.internal;

import com.mars.cloud.rocketmq.publish.LocalTransaction;

/**
 * 发布器与事务监听器之间的线程局部传递槽。
 *
 * <p>binder 在发送线程同步调用事务监听器，所以发布器把本地事务放进当前线程的槽，监听器取出执行并把结果放回。
 * 不用 binder 的 {@code TRANSACTIONAL_ARGS} 头传递：头映射器会把该对象的字符串形式当用户属性发到消费侧。
 */
public final class PendingLocalTransaction {

    private static final ThreadLocal<PendingLocalTransaction> CURRENT = new ThreadLocal<>();

    private final LocalTransaction transaction;
    private boolean executed;
    private Thread executedOn;
    private RuntimeException failure;

    private PendingLocalTransaction(LocalTransaction transaction) {
        this.transaction = transaction;
    }

    /** 在当前线程登记待执行的本地事务；前一个未取走的登记是编程错误。 */
    public static PendingLocalTransaction register(LocalTransaction transaction) {
        if (CURRENT.get() != null) {
            CURRENT.remove();
            throw new IllegalStateException("当前线程已有未完成的事务消息发送，不能嵌套");
        }
        PendingLocalTransaction pending = new PendingLocalTransaction(transaction);
        CURRENT.set(pending);
        return pending;
    }

    /** 监听器取走当前线程的登记；没有登记返回 null。 */
    static PendingLocalTransaction take() {
        PendingLocalTransaction pending = CURRENT.get();
        CURRENT.remove();
        return pending;
    }

    /** 发布器在发送结束后清理当前线程。 */
    public void release() {
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }

    void execute() {
        executedOn = Thread.currentThread();
        try {
            transaction.execute();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            executed = true;
        }
    }

    public boolean executed() {
        return executed;
    }

    public Thread executedOn() {
        return executedOn;
    }

    public RuntimeException failure() {
        return failure;
    }
}
