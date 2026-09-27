package com.mars.cloud.job.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 执行器注册：启动后立即注册，此后每 30 秒一次（调度中心 90 秒没有心跳即摘除），关闭时摘除。
 *
 * <p>调度中心不可达时只在状态从成功变为失败时打一条告警，恢复时打一条 INFO，其间不重复打印。
 * 调度中心不可用时周期任务只是暂停，服务本身照常运行。
 */
public final class RegistryHeartbeat {

    static final Duration INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RegistryHeartbeat.class);

    private final AdminClient admin;
    private final Protocol.RegistryRequest registration;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    private ScheduledFuture<?> task;
    /** null 表示还没有结果；只由调度线程读写。 */
    private Boolean registered;

    public RegistryHeartbeat(AdminClient admin, String appName, String address, ScheduledExecutorService scheduler,
                             Duration interval) {
        this.admin = admin;
        this.registration = new Protocol.RegistryRequest(Protocol.EXECUTOR_GROUP, appName, address);
        this.scheduler = scheduler;
        this.interval = interval;
    }

    public synchronized void start() {
        task = scheduler.scheduleWithFixedDelay(this::beat, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void beat() {
        AdminClient.Outcome outcome = admin.registry(registration);
        if (outcome.succeeded() && !Boolean.TRUE.equals(registered)) {
            log.info("执行器已在调度中心注册：app={}，address={}", registration.registryKey(), registration.registryValue());
        } else if (!outcome.succeeded() && !Boolean.FALSE.equals(registered)) {
            log.warn("执行器向调度中心注册失败，周期任务暂停，每 {}重试，恢复后记录一条 INFO：app={}，原因：{}",
                    describe(interval), registration.registryKey(), outcome.detail());
        }
        registered = outcome.succeeded();
    }

    private static String describe(Duration interval) {
        return interval.toMillis() % 1000 == 0 ? interval.toSeconds() + " 秒" : interval.toMillis() + " 毫秒";
    }

    /** 停止心跳并从调度中心摘除；摘除失败只记 INFO，调度中心 90 秒后会自行摘除。 */
    public synchronized void stop() {
        if (task == null) {
            return;
        }
        task.cancel(false);
        task = null;
        AdminClient.Outcome outcome = admin.registryRemove(registration);
        if (outcome.succeeded()) {
            log.info("执行器已从调度中心摘除：app={}，address={}", registration.registryKey(), registration.registryValue());
        } else {
            log.info("执行器摘除未成功，调度中心将在 90 秒无心跳后自行摘除：app={}，原因：{}",
                    registration.registryKey(), outcome.detail());
        }
    }
}
