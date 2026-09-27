package com.mars.cloud.job.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 执行器的启动与关闭。
 *
 * <p>生命周期阶段排在 Web 服务器启动之后、Web 服务器优雅关闭之后停止（Spring Boot 两者分别是
 * {@code DEFAULT_PHASE - 2048} 与 {@code DEFAULT_PHASE - 1024}）：任务方法可以依赖完整启动的应用，
 * 关闭时正在处理的 Web 请求先结束。
 *
 * <p>启动：创建日志目录、同步绑定执行器端口（失败即启动失败）、开始回调发送与注册心跳、每天清理过期日志。
 * 关闭：先从调度中心摘除并停止接收，再在 {@code shutdownTimeout} 内等待执行中的任务，最后在剩余时间内发送回调。
 */
public final class JobExecutor implements SmartLifecycle {

    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 1536;
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    static final int CALLBACK_CAPACITY = 10_000;

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final ExecutorSettings settings;
    private final JobHandlerRegistry registry;
    private final JobTracing tracing;
    private final Clock clock;
    private final Duration heartbeatInterval;
    private volatile boolean running;
    private ScheduledExecutorService maintenance;
    private CallbackSender callbacks;
    private JobDispatcher dispatcher;
    private ExecutorHttpServer server;
    private RegistryHeartbeat heartbeat;
    private String registeredAddress;

    public JobExecutor(ExecutorSettings settings, JobHandlerRegistry registry, JobTracing tracing) {
        this(settings, registry, tracing, Clock.systemDefaultZone(), RegistryHeartbeat.INTERVAL);
    }

    JobExecutor(ExecutorSettings settings, JobHandlerRegistry registry, JobTracing tracing, Clock clock,
                Duration heartbeatInterval) {
        this.settings = settings;
        this.registry = registry;
        this.tracing = tracing;
        this.clock = clock;
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        JobLogFiles logs = new JobLogFiles(settings.logPath(), clock);
        logs.initialize();
        AdminClient admin = new AdminClient(settings.adminAddresses(), settings.accessToken(), settings.adminTimeout());
        callbacks = new CallbackSender(admin, CALLBACK_CAPACITY, Duration.ofSeconds(1));
        dispatcher = new JobDispatcher(registry, logs, callbacks, tracing, IDLE_TIMEOUT);
        server = new ExecutorHttpServer(new InetSocketAddress(settings.bindAddress(), settings.port()),
                settings.accessToken(), dispatcher);
        server.start();
        callbacks.start();
        registeredAddress = settings.registeredAddress(server.port());
        maintenance = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mars-job-registry");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(() -> logs.purge(settings.logRetention()), 0, 1, TimeUnit.DAYS);
        heartbeat = new RegistryHeartbeat(admin, settings.appName(), registeredAddress, maintenance, heartbeatInterval);
        heartbeat.start();
        running = true;
        log.info("执行器已启动：app={}，监听 {}:{}，注册地址 {}，任务 {}", settings.appName(),
                settings.bindAddress() == null ? "*" : settings.bindAddress().getHostAddress(), server.port(),
                registeredAddress, registry.names());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        long deadline = System.nanoTime() + settings.shutdownTimeout().toNanos();
        heartbeat.stop();
        server.stop();
        dispatcher.shutdown(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        maintenance.shutdownNow();
        long remaining = Math.max(0, deadline - System.nanoTime());
        callbacks.stop(Duration.ofNanos(Math.max(remaining, TimeUnit.SECONDS.toNanos(1))));
        log.info("执行器已停止：app={}", settings.appName());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 实际监听的端口；未启动时为 -1。 */
    public int port() {
        return server == null ? -1 : server.port();
    }

    /** 注册给调度中心的地址；未启动时为 null。 */
    public String registeredAddress() {
        return registeredAddress;
    }
}
