package com.mars.cloud.job.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 接收调度中心的触发并执行任务方法。
 *
 * <p>每个任务编号一个串行的工作线程，空闲超过 {@code idleTimeout} 后退出，下次触发时重建。
 * {@code /run} 只做校验与入队，立即返回，执行在工作线程里进行（调度中心调用执行器的超时默认 3 秒）。
 * 执行结果经回调队列异步回报调度中心。
 *
 * <p>锁顺序固定为先本对象、后工作线程对象；工作线程在持有自己的锁时不获取本对象的锁。
 *
 * <p>中断只落在任务方法执行期间：终止、超时与覆盖之前调度都中断执行线程，执行结束时在工作线程对象的锁内
 * 标记结束并清除中断标志，此后的请求不再中断这个线程，下一次执行不会带着上一次的中断开始。
 */
public final class JobDispatcher {

    static final String KILLED = "任务已被终止";
    static final String KILLED_WHILE_QUEUED = "任务已被终止，排队中的这次触发未执行";
    static final String COVERED = "被新的触发覆盖（阻塞处理策略为覆盖之前调度）";
    static final String COVERED_WHILE_QUEUED = "被新的触发覆盖，排队中的这次触发未执行";
    static final String SHUTDOWN_WHILE_QUEUED = "执行器关闭，排队中的这次触发未执行";
    static final String SHUTDOWN_INTERRUPTED = "执行器关闭，等待超时后中断了执行中的任务";
    static final int MAX_MESSAGE_LENGTH = 4000;

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JobHandlerRegistry registry;
    private final JobLogFiles logs;
    private final Consumer<Protocol.CallbackRequest> results;
    private final JobTracing tracing;
    private final Duration idleTimeout;
    private final ScheduledExecutorService watchdog;
    private final Map<Integer, Worker> workers = new HashMap<>();
    private boolean closed;

    public JobDispatcher(JobHandlerRegistry registry, JobLogFiles logs, Consumer<Protocol.CallbackRequest> results,
                         JobTracing tracing, Duration idleTimeout) {
        this.registry = registry;
        this.logs = logs;
        this.results = results;
        this.tracing = tracing;
        this.idleTimeout = idleTimeout;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mars-job-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 调度中心的 {@code /run}。 */
    public Protocol.Response run(Protocol.TriggerRequest trigger) {
        if (!Protocol.BEAN.equals(trigger.glueType())) {
            return Protocol.Response.fail("执行器只执行 BEAN 类型的任务，不执行调度中心下发的脚本，收到 glueType=" + trigger.glueType());
        }
        JobMethod method = registry.find(trigger.executorHandler());
        if (method == null) {
            return Protocol.Response.fail("执行器上没有名为 " + trigger.executorHandler() + " 的任务方法，已登记：" + registry.names());
        }
        BlockStrategy strategy = BlockStrategy.of(trigger.executorBlockStrategy());
        synchronized (this) {
            if (closed) {
                return Protocol.Response.fail("执行器正在关闭，不再接受触发");
            }
            Worker worker = workers.computeIfAbsent(trigger.jobId(), Worker::new);
            return worker.submit(new Pending(trigger, method), strategy);
        }
    }

    /** 调度中心的 {@code /idleBeat}：任务正在执行或有排队时返回失败，供忙碌转移路由选择其他执行器。 */
    public synchronized Protocol.Response idleBeat(int jobId) {
        Worker worker = workers.get(jobId);
        if (worker != null && worker.busy()) {
            return Protocol.Response.fail("任务正在执行或有排队的触发");
        }
        return Protocol.Response.success();
    }

    /** 调度中心的 {@code /kill}：取消排队中的触发并中断执行中的一次，它们都以失败回报。 */
    public Protocol.Response kill(int jobId) {
        Worker worker;
        synchronized (this) {
            worker = workers.get(jobId);
        }
        if (worker == null) {
            return Protocol.Response.success("任务不在执行");
        }
        worker.cancel(KILLED_WHILE_QUEUED, KILLED);
        return Protocol.Response.success();
    }

    /** 调度中心的 {@code /log}。 */
    public Protocol.Response log(Protocol.LogRequest request) {
        return Protocol.Response.success(logs.read(request.logDateTim(), request.logId(), request.fromLineNum()));
    }

    /**
     * 关闭：不再接受触发，取消排队中的触发，在 {@code timeout} 内等待执行中的任务结束，超时则中断。
     */
    public void shutdown(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Worker> snapshot;
        synchronized (this) {
            closed = true;
            snapshot = new ArrayList<>(workers.values());
        }
        for (Worker worker : snapshot) {
            worker.stopAccepting();
        }
        for (Worker worker : snapshot) {
            if (!worker.awaitIdle(deadline)) {
                worker.cancel(SHUTDOWN_WHILE_QUEUED, SHUTDOWN_INTERRUPTED);
            }
        }
        for (Worker worker : snapshot) {
            worker.join(Duration.ofSeconds(2));
        }
        watchdog.shutdownNow();
    }

    /** 空闲的工作线程退出前调用；期间有新触发入队则继续工作。 */
    private synchronized boolean retire(Worker worker) {
        synchronized (worker) {
            if (!worker.idle()) {
                return false;
            }
            workers.remove(worker.jobId, worker);
            worker.thread = null;
            return true;
        }
    }

    private void report(Protocol.TriggerRequest trigger, int code, String message) {
        String truncated = message != null && message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        results.accept(new Protocol.CallbackRequest(trigger.logId(), trigger.logDateTime(), code, truncated));
    }

    private record Pending(Protocol.TriggerRequest trigger, JobMethod method) {
        long logId() {
            return trigger.logId();
        }
    }

    enum BlockStrategy {
        SERIAL_EXECUTION, DISCARD_LATER, COVER_EARLY;

        static BlockStrategy of(String value) {
            for (BlockStrategy strategy : values()) {
                if (strategy.name().equals(value)) {
                    return strategy;
                }
            }
            return SERIAL_EXECUTION;
        }
    }

    private final class Worker implements Runnable {

        private final int jobId;
        private final Deque<Pending> queue = new ArrayDeque<>();
        private Pending running;
        private Thread thread;
        private boolean stopping;
        /** 执行中的这次被要求中断时的结果（取第一次请求的原因）；null 表示没有被要求中断。 */
        private int interruptCode;
        private String interruptMessage;
        /** 执行中的这次已经结束，不再接受中断。 */
        private boolean finished;

        Worker(int jobId) {
            this.jobId = jobId;
        }

        synchronized Protocol.Response submit(Pending pending, BlockStrategy strategy) {
            if (stopping) {
                return Protocol.Response.fail("执行器正在关闭，不再接受触发");
            }
            if (holds(pending.logId())) {
                return Protocol.Response.fail("重复的触发：调度日志编号 " + pending.logId() + " 已在执行或排队");
            }
            boolean busy = running != null || !queue.isEmpty();
            if (busy && strategy == BlockStrategy.DISCARD_LATER) {
                return Protocol.Response.fail("任务正在执行，阻塞处理策略为丢弃后续调度，本次触发已丢弃");
            }
            if (busy && strategy == BlockStrategy.COVER_EARLY) {
                cancelQueued(COVERED_WHILE_QUEUED);
                interruptRunning(Protocol.FAIL, COVERED);
            }
            logs.begin(pending.logId());
            queue.addLast(pending);
            if (thread == null) {
                thread = new Thread(this, "mars-job-" + jobId);
                thread.setDaemon(true);
                thread.start();
            }
            notifyAll();
            return Protocol.Response.success();
        }

        synchronized boolean busy() {
            return running != null || !queue.isEmpty();
        }

        boolean idle() {
            return running == null && queue.isEmpty();
        }

        private boolean holds(long logId) {
            if (running != null && running.logId() == logId) {
                return true;
            }
            for (Pending pending : queue) {
                if (pending.logId() == logId) {
                    return true;
                }
            }
            return false;
        }

        synchronized void cancel(String queuedReason, String runningReason) {
            cancelQueued(queuedReason);
            interruptRunning(Protocol.FAIL, runningReason);
        }

        synchronized void stopAccepting() {
            stopping = true;
            cancelQueued(SHUTDOWN_WHILE_QUEUED);
            notifyAll();
        }

        private void cancelQueued(String reason) {
            Pending pending;
            while ((pending = queue.pollFirst()) != null) {
                logs.append(pending.trigger().logDateTime(), pending.logId(), reason);
                logs.end(pending.logId());
                report(pending.trigger(), Protocol.FAIL, reason);
            }
        }

        /** 每次请求都中断执行线程（任务方法可能吞掉了上一次中断）；结果保留第一次请求的原因。 */
        private void interruptRunning(int code, String message) {
            if (running == null || thread == null || finished) {
                return;
            }
            if (interruptMessage == null) {
                interruptCode = code;
                interruptMessage = message;
            }
            thread.interrupt();
        }

        private synchronized void timeout(Pending pending, int seconds) {
            if (running == pending) {
                interruptRunning(Protocol.TIMEOUT, "执行超时：超过 " + seconds + " 秒，已中断");
            }
        }

        synchronized boolean awaitIdle(long deadline) {
            while (!idle()) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return false;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        void join(Duration timeout) {
            Thread current;
            synchronized (this) {
                current = thread;
            }
            if (current == null) {
                return;
            }
            try {
                current.join(timeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Pending pending = take();
                    if (pending == null) {
                        synchronized (this) {
                            if (stopping) {
                                thread = null;
                                notifyAll();
                                return;
                            }
                        }
                        if (retire(this)) {
                            return;
                        }
                        continue;
                    }
                    execute(pending);
                    synchronized (this) {
                        running = null;
                        finished = false;
                        interruptCode = 0;
                        interruptMessage = null;
                        notifyAll();
                    }
                }
            } finally {
                // 正常退出时这里已复位；线程因意外错误退出时复位状态，下次触发重建线程。
                synchronized (this) {
                    if (thread == Thread.currentThread()) {
                        thread = null;
                        running = null;
                        finished = false;
                        interruptMessage = null;
                        notifyAll();
                    }
                }
            }
        }

        /** 取下一次触发；空闲超时或正在关闭时返回 null。 */
        private synchronized Pending take() {
            long deadline = System.nanoTime() + idleTimeout.toNanos();
            while (queue.isEmpty() && !stopping) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return null;
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    // 中断只用于停止执行中的任务，等待期间收到的中断没有意义
                }
            }
            Pending pending = queue.pollFirst();
            running = pending;
            return pending;
        }

        /**
         * 执行一次并回报结果。任务方法以外的步骤（链路追踪、超时定时器、执行日志）出错时同样以失败回报，
         * 工作线程继续处理后面的触发。
         */
        private void execute(Pending pending) {
            Protocol.TriggerRequest trigger = pending.trigger();
            String name = pending.method().name();
            RunContext context = new RunContext(trigger, pending.method(), logs);
            int timeoutSeconds = trigger.executorTimeout();
            ScheduledFuture<?> timer = null;
            int code = Protocol.SUCCESS;
            String message = null;
            try {
                if (timeoutSeconds > 0) {
                    timer = watchdog.schedule(() -> timeout(pending, timeoutSeconds), timeoutSeconds, TimeUnit.SECONDS);
                }
                try (JobTracing.Scope scope = tracing.start(name, jobId, trigger.logId())) {
                    context.record("开始执行任务 " + name + (scope.traceId() == null ? "" : "，traceId=" + scope.traceId())
                            + "，参数=" + context.param() + "，分片 " + context.shardIndex() + "/" + context.shardTotal());
                    try {
                        pending.method().invoke(context);
                    } catch (Throwable failure) {
                        scope.error(failure);
                        code = Protocol.FAIL;
                        message = failure.toString();
                        context.record("任务抛出异常：" + System.lineSeparator() + RunContext.stackTrace(failure));
                        log.warn("任务 {} 执行失败：jobId={}，logId={}", name, jobId, trigger.logId(), failure);
                    }
                }
            } catch (Throwable unexpected) {
                code = Protocol.FAIL;
                message = "执行器内部错误：" + unexpected.getClass().getName();
                log.error("任务 {} 的执行过程出错：jobId={}，logId={}", name, jobId, trigger.logId(), unexpected);
            } finally {
                if (timer != null) {
                    timer.cancel(false);
                }
            }
            synchronized (this) {
                finished = true;
                Thread.interrupted();
                if (interruptMessage != null) {
                    code = interruptCode;
                    message = interruptMessage;
                }
            }
            try {
                context.record(code == Protocol.SUCCESS ? "任务执行成功" : "任务执行失败：" + message);
            } catch (RuntimeException unwritable) {
                log.error("任务 {} 的执行日志写入失败：jobId={}，logId={}", name, jobId, trigger.logId(), unwritable);
            } finally {
                logs.end(trigger.logId());
                report(trigger, code, message);
            }
        }
    }
}
