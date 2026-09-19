package com.mars.cloud.mvc.workthread;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @since 2025-05-23 16:12
 */
@Slf4j
public class DefaultThreadFactory implements ThreadFactory {

    private final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
    private final ThreadGroup group;
    private final String prefix;

    private static final UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER =
            new UncaughtExceptionHandler();

    public DefaultThreadFactory() {
        this("pool");
    }

    public DefaultThreadFactory(String prefix) {
        this.prefix = prefix;
        //SecurityManager s = System.getSecurityManager();
        //this.group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        this.group = Thread.currentThread().getThreadGroup();
    }

    @Override
    public Thread newThread(@NonNull Runnable r) {
        Thread t =
                new Thread(group, r, this.prefix + "-thread-" + this.POOL_NUMBER.getAndIncrement());
        t.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);
        return t;
    }

    public static class UncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                log.error("Uncaught exception occur ", e);
            } catch (Throwable err) {
                // Doing nothing (probably due to an oom issue)
            }
        }
    }
}
