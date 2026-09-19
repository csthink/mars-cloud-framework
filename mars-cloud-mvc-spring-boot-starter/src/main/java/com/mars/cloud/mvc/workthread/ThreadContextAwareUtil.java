package com.mars.cloud.mvc.workthread;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;

/**
 * @since 2025-05-23 16:14
 */
@Slf4j
public class ThreadContextAwareUtil extends ThreadPoolExecutor {

    /**
     * A threadPool where task threads take MDC from the submitting thread.
     */
    public static ThreadContextAwareUtil newWithInheritedMdc(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        return new ThreadContextAwareUtil(
                corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    private ThreadContextAwareUtil(
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    /**
     * All executions will have MDC injected. all delegate to this.
     */
    @Override
    public void execute(Runnable runnable) {
        super.execute(wrap(runnable));
    }

    public static Runnable wrap(final Runnable runnable) {
        Map<String, String> copyOfContextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (!Objects.isNull(copyOfContextMap)) {
                    MDC.setContextMap(copyOfContextMap);
                }

                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
