package com.mars.cloud.mvc.workthread;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @since 2025-05-23 16:11
 */
@Slf4j
public abstract class AbstractWorkThread<P, R> implements Worker<P, R> {

    private final ThreadPoolExecutor executor;

    public AbstractWorkThread() {
        this.executor = ThreadPoolWorker.singleInstance().getExecutor();
        this.executor.setThreadFactory(new DefaultThreadFactory());
    }

    public AbstractWorkThread(ThreadPoolExecutor executor) {
        this.executor = executor;
        this.executor.setThreadFactory(new DefaultThreadFactory());
    }

    public Map<String, R> start(RequestFacade<P> facade) throws Exception {
        Map<String, R> result = new ConcurrentHashMap<>();

        int count = facade.count();
        CountDownLatch latch;

        latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            executor.submit(new Task(latch, facade, result, Thread.currentThread().getName(), i));
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            log.warn("WorkThread start occur error", e);
        }

        return result;
    }

    @AllArgsConstructor
    class Task implements Runnable {

        private CountDownLatch latch;
        private RequestFacade<P> facade;
        private Map<String, R> result;
        private String currentThreadName;
        private int index;

        @Override
        public void run() {
            String name = Thread.currentThread().getName() + "-[" + (index + 1) + "]";
            long start = System.currentTimeMillis();

            try {
                execute(facade.getParams().get(index), result);
            } catch (Exception e) {
                log.warn("sub thread: {} execute occur error", name, e);
            } finally {
                if (latch != null) {
                    latch.countDown();
                    log.debug(
                            "{} sub thread: {}, elapsed {} ms",
                            currentThreadName,
                            name,
                            (System.currentTimeMillis() - start)
                    );
                }
            }
        }
    }
}
