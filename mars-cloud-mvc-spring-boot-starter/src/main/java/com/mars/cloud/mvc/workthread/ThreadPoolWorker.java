package com.mars.cloud.mvc.workthread;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @since 2025-05-23 16:15
 */
@Slf4j
@Getter
public class ThreadPoolWorker {

    private final ThreadPoolExecutor executor;

    private static final int DEFAULT_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final ThreadPoolWorker SINGLE = new ThreadPoolWorker();

    private ThreadPoolWorker() {
        this.executor =
                ThreadContextAwareUtil.newWithInheritedMdc(
                        DEFAULT_POOL_SIZE * 2,
                        DEFAULT_POOL_SIZE * 5,
                        600L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(3000));

        this.executor.setThreadFactory(new DefaultThreadFactory());
        this.executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        log.info("Initial thread pool, core pool size: {}, max pool size:{}, work queue size:{}",
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue().size());
    }

    public static ThreadPoolWorker singleInstance() {
        return SINGLE;
    }
}
