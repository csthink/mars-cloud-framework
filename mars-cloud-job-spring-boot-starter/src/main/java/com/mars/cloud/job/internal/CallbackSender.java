package com.mars.cloud.job.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 把执行结果回报调度中心（{@code POST /api/callback}）。
 *
 * <p>单线程从有界队列批量取出（每批最多 {@value #BATCH_SIZE} 条）发送；失败按 1、2、4 秒递增、最长 30 秒退避后重发同一批。
 * 队列满时丢弃最旧的一条并告警。不写重试文件：进程退出时仍未送达的结果，调度中心在执行器下线 10 分钟后标为失败。
 */
public final class CallbackSender implements Consumer<Protocol.CallbackRequest> {

    static final int BATCH_SIZE = 100;
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(CallbackSender.class);

    private final AdminClient admin;
    private final LinkedBlockingDeque<Protocol.CallbackRequest> queue;
    private final Duration initialBackoff;
    private final Thread thread;
    private volatile boolean stopping;
    /** 当前批次；发送线程独占，关闭时用来统计未送达的条数。 */
    private volatile List<Protocol.CallbackRequest> inFlight = List.of();

    public CallbackSender(AdminClient admin, int capacity, Duration initialBackoff) {
        this.admin = admin;
        this.queue = new LinkedBlockingDeque<>(capacity);
        this.initialBackoff = initialBackoff;
        this.thread = new Thread(this::loop, "mars-job-callback");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    @Override
    public void accept(Protocol.CallbackRequest result) {
        while (!queue.offerLast(result)) {
            Protocol.CallbackRequest dropped = queue.pollFirst();
            if (dropped != null) {
                log.warn("回调队列已满，丢弃最旧的一条执行结果：logId={}，handleCode={}", dropped.logId(), dropped.handleCode());
            }
        }
    }

    private void loop() {
        Duration backoff = initialBackoff;
        boolean failing = false;
        while (!stopping || !queue.isEmpty() || !inFlight.isEmpty()) {
            try {
                if (inFlight.isEmpty()) {
                    Protocol.CallbackRequest first = queue.pollFirst(200, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    List<Protocol.CallbackRequest> batch = new ArrayList<>();
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    inFlight = List.copyOf(batch);
                }
                AdminClient.Outcome outcome = admin.callback(inFlight);
                if (outcome.succeeded()) {
                    if (failing) {
                        log.info("执行结果回报恢复");
                    }
                    inFlight = List.of();
                    failing = false;
                    backoff = initialBackoff;
                    continue;
                }
                if (!failing) {
                    log.warn("执行结果回报调度中心失败，按退避重发：{} 条，原因：{}", inFlight.size(), outcome.detail());
                    failing = true;
                }
                if (stopping) {
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(backoff.toMillis());
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            } catch (InterruptedException e) {
                if (stopping) {
                    return;
                }
            }
        }
    }

    /**
     * 停止接收并在 {@code timeout} 内尽量发完；发不完的条数写一条告警。
     */
    public void stop(Duration timeout) {
        stopping = true;
        try {
            thread.join(Math.max(1, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int undelivered = queue.size() + inFlight.size();
        if (undelivered > 0) {
            log.warn("执行器关闭时有 {} 条执行结果未送达调度中心，调度中心将在执行器下线后把它们标为失败", undelivered);
        }
    }
}
