package com.mars.cloud.job.internal;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.job.JobContext;
import com.mars.cloud.job.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobDispatcherTest {

    /** 测试用的任务方法。{@code blocking} 在信号量上等待，测试据此控制它何时结束。 */
    public static class TestJobs {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Semaphore release = new Semaphore(0);
        volatile CountDownLatch started = new CountDownLatch(1);

        @JobHandler("quick")
        public void quick(JobContext context) {
            calls.add("quick:" + context.param() + ":" + context.shardIndex() + "/" + context.shardTotal());
            context.log("quick ran with {}", context.param());
        }

        @JobHandler("failing")
        public void failing() {
            throw new IllegalStateException("boom");
        }

        @JobHandler("blocking")
        public void blocking(JobContext context) throws InterruptedException {
            started.countDown();
            release.acquire();
            calls.add("blocking:" + context.logId());
        }

        final CountDownLatch firstInterrupt = new CountDownLatch(1);

        /** 吞掉第一次中断继续等待，第二次中断才结束。 */
        @JobHandler("stubborn")
        public void stubborn() {
            try {
                release.acquire();
            } catch (InterruptedException first) {
                calls.add("stubborn:swallowed");
                firstInterrupt.countDown();
            }
            try {
                release.acquire();
            } catch (InterruptedException second) {
                calls.add("stubborn:stopped");
            }
        }

        @JobHandler("identity")
        public void identity() {
            calls.add("identity:" + CallerContextHolder.current().isPresent());
        }
    }

    @TempDir
    Path logDirectory;

    private final List<Protocol.CallbackRequest> results = Collections.synchronizedList(new ArrayList<>());
    private TestJobs jobs;
    private JobLogFiles logs;
    private JobDispatcher dispatcher;
    private long nextLogId = 1;

    private JobHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        jobs = new TestJobs();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("testJobs", new RootBeanDefinition(TestJobs.class, () -> jobs));
        registry = new JobHandlerRegistry();
        registry.setBeanFactory(factory);
        registry.afterSingletonsInstantiated();
        logs = new JobLogFiles(logDirectory, Clock.systemDefaultZone());
        logs.initialize();
        dispatcher = dispatcher(Duration.ofSeconds(90), JobTracing.NONE);
    }

    private JobDispatcher dispatcher(Duration idleTimeout, JobTracing tracing) {
        return new JobDispatcher(registry, logs, results::add, tracing, idleTimeout);
    }

    @AfterEach
    void tearDown() {
        jobs.release.release(100);
        dispatcher.shutdown(Duration.ofSeconds(2));
    }

    private Protocol.TriggerRequest trigger(int jobId, String handler, String strategy, int timeout, String param) {
        return new Protocol.TriggerRequest(jobId, handler, param, strategy, timeout, nextLogId++, System.currentTimeMillis(),
                Protocol.BEAN, null, 0, 0, 1);
    }

    private Protocol.TriggerRequest trigger(int jobId, String handler) {
        return trigger(jobId, handler, "SERIAL_EXECUTION", 0, "");
    }

    private Protocol.CallbackRequest resultFor(Protocol.TriggerRequest trigger) {
        await().atMost(Duration.ofSeconds(5)).until(() -> results.stream().anyMatch(r -> r.logId() == trigger.logId()));
        return results.stream().filter(r -> r.logId() == trigger.logId()).findFirst().orElseThrow();
    }

    private void awaitStarted() throws InterruptedException {
        assertThat(jobs.started.await(5, TimeUnit.SECONDS)).isTrue();
        jobs.started = new CountDownLatch(1);
    }

    private String logOf(Protocol.TriggerRequest trigger) throws Exception {
        return Files.readString(logs.path(trigger.logDateTime(), trigger.logId()));
    }

    @Test
    void scriptsFromTheSchedulerAreNeverExecuted() {
        Protocol.TriggerRequest glue = new Protocol.TriggerRequest(1, "", "", "SERIAL_EXECUTION", 0, 1, System.currentTimeMillis(),
                "GLUE_SHELL", "echo hi", 1, 0, 1);
        Protocol.Response response = dispatcher.run(glue);
        assertThat(response.succeeded()).isFalse();
        assertThat(response.msg()).isEqualTo("执行器只执行 BEAN 类型的任务，不执行调度中心下发的脚本，收到 glueType=GLUE_SHELL");
        assertThat(results).isEmpty();
    }

    @Test
    void anUnknownHandlerIsRejectedWithTheRegisteredNames() {
        Protocol.Response response = dispatcher.run(trigger(1, "missing"));
        assertThat(response.msg()).isEqualTo("执行器上没有名为 missing 的任务方法，已登记：[blocking, failing, identity, quick, stubborn]");
    }

    @Test
    void aSuccessfulRunIsLoggedAndReported() throws Exception {
        Protocol.TriggerRequest trigger = new Protocol.TriggerRequest(3, "quick", "p1", "SERIAL_EXECUTION", 0, 77,
                System.currentTimeMillis(), Protocol.BEAN, null, 0, 1, 3);
        assertThat(dispatcher.run(trigger).succeeded()).isTrue();
        Protocol.CallbackRequest result = resultFor(trigger);
        assertThat(result.handleCode()).isEqualTo(Protocol.SUCCESS);
        assertThat(result.handleMsg()).isNull();
        assertThat(result.logDateTim()).isEqualTo(trigger.logDateTime());
        assertThat(jobs.calls).containsExactly("quick:p1:1/3");
        assertThat(logOf(trigger)).contains("开始执行任务 quick，参数=p1，分片 1/3").contains("quick ran with p1").contains("任务执行成功");
        Protocol.LogResult read = (Protocol.LogResult) dispatcher.log(new Protocol.LogRequest(trigger.logDateTime(), 77, 1)).data();
        assertThat(read.logContent()).contains("quick ran with p1");
        assertThat(read.isEnd()).isTrue();
    }

    @Test
    void aFailureIsReportedWithItsSummaryAndTheStackGoesToTheLog() throws Exception {
        Protocol.TriggerRequest trigger = trigger(4, "failing");
        dispatcher.run(trigger);
        Protocol.CallbackRequest result = resultFor(trigger);
        assertThat(result.handleCode()).isEqualTo(Protocol.FAIL);
        assertThat(result.handleMsg()).isEqualTo("java.lang.IllegalStateException: boom");
        assertThat(logOf(trigger)).contains("任务抛出异常").contains("at com.mars.cloud.job.internal.JobDispatcherTest$TestJobs.failing")
                .contains("任务执行失败：java.lang.IllegalStateException: boom");
    }

    @Test
    void serialTriggersQueueBehindTheRunningOne() throws Exception {
        Protocol.TriggerRequest first = trigger(5, "blocking");
        Protocol.TriggerRequest second = trigger(5, "blocking");
        dispatcher.run(first);
        awaitStarted();
        assertThat(dispatcher.run(second).succeeded()).isTrue();
        assertThat(dispatcher.idleBeat(5).succeeded()).isFalse();
        jobs.release.release(2);
        assertThat(resultFor(first).handleCode()).isEqualTo(Protocol.SUCCESS);
        assertThat(resultFor(second).handleCode()).isEqualTo(Protocol.SUCCESS);
        assertThat(jobs.calls).containsExactly("blocking:" + first.logId(), "blocking:" + second.logId());
        await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.idleBeat(5).succeeded());
    }

    @Test
    void discardLaterDropsATriggerWhileBusy() throws Exception {
        Protocol.TriggerRequest running = trigger(6, "blocking");
        dispatcher.run(running);
        awaitStarted();
        Protocol.Response dropped = dispatcher.run(trigger(6, "blocking", "DISCARD_LATER", 0, ""));
        assertThat(dropped.msg()).isEqualTo("任务正在执行，阻塞处理策略为丢弃后续调度，本次触发已丢弃");
        jobs.release.release();
        assertThat(resultFor(running).handleCode()).isEqualTo(Protocol.SUCCESS);
        assertThat(results).hasSize(1);
    }

    @Test
    void coverEarlyReplacesTheRunningAndQueuedTriggers() throws Exception {
        Protocol.TriggerRequest running = trigger(7, "blocking");
        Protocol.TriggerRequest queued = trigger(7, "blocking");
        Protocol.TriggerRequest cover = trigger(7, "blocking", "COVER_EARLY", 0, "");
        dispatcher.run(running);
        awaitStarted();
        dispatcher.run(queued);
        assertThat(dispatcher.run(cover).succeeded()).isTrue();
        assertThat(resultFor(queued)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.FAIL, JobDispatcher.COVERED_WHILE_QUEUED);
        assertThat(resultFor(running)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.FAIL, JobDispatcher.COVERED);
        awaitStarted();
        jobs.release.release();
        assertThat(resultFor(cover).handleCode()).isEqualTo(Protocol.SUCCESS);
    }

    @Test
    void aRunLongerThanItsTimeoutIsInterruptedAndReportedAsTimedOut() {
        Protocol.TriggerRequest slow = trigger(8, "blocking", "SERIAL_EXECUTION", 1, "");
        dispatcher.run(slow);
        assertThat(resultFor(slow)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.TIMEOUT, "执行超时：超过 1 秒，已中断");
    }

    @Test
    void killCancelsQueuedTriggersAndInterruptsTheRunningOne() throws Exception {
        Protocol.TriggerRequest running = trigger(9, "blocking");
        Protocol.TriggerRequest queued = trigger(9, "blocking");
        dispatcher.run(running);
        awaitStarted();
        dispatcher.run(queued);
        assertThat(dispatcher.kill(9).succeeded()).isTrue();
        assertThat(resultFor(queued).handleMsg()).isEqualTo(JobDispatcher.KILLED_WHILE_QUEUED);
        assertThat(resultFor(running).handleMsg()).isEqualTo(JobDispatcher.KILLED);
        assertThat(dispatcher.kill(404)).extracting(Protocol.Response::succeeded, Protocol.Response::data)
                .containsExactly(true, "任务不在执行");
    }

    @Test
    void theSameLogIdIsAcceptedOnlyOnce() throws Exception {
        Protocol.TriggerRequest running = trigger(10, "blocking");
        dispatcher.run(running);
        awaitStarted();
        assertThat(dispatcher.run(running).msg()).isEqualTo("重复的触发：调度日志编号 " + running.logId() + " 已在执行或排队");
    }

    @Test
    void aLogIdStillQueuedIsNotAcceptedAgain() throws Exception {
        Protocol.TriggerRequest running = trigger(16, "blocking");
        Protocol.TriggerRequest queued = trigger(16, "blocking");
        dispatcher.run(running);
        awaitStarted();
        dispatcher.run(queued);
        assertThat(dispatcher.run(queued).msg()).isEqualTo("重复的触发：调度日志编号 " + queued.logId() + " 已在执行或排队");
    }

    @Test
    void aKillAfterATimeoutInterruptsAMethodThatSwallowedTheFirstInterrupt() throws Exception {
        Protocol.TriggerRequest slow = trigger(14, "stubborn", "SERIAL_EXECUTION", 1, "");
        dispatcher.run(slow);
        assertThat(jobs.firstInterrupt.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatcher.kill(14).succeeded()).isTrue();
        assertThat(resultFor(slow)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.TIMEOUT, "执行超时：超过 1 秒，已中断");
        assertThat(jobs.calls).containsExactly("stubborn:swallowed", "stubborn:stopped");
    }

    @Test
    void anInterruptRequestedWhileAResultIsReportedDoesNotReachTheNextRun() throws Exception {
        dispatcher.shutdown(Duration.ofSeconds(1));
        Protocol.TriggerRequest first = trigger(15, "quick");
        Protocol.TriggerRequest cover = trigger(15, "blocking", "COVER_EARLY", 0, "");
        AtomicReference<JobDispatcher> current = new AtomicReference<>();
        // 覆盖之前调度的触发在第一次执行的方法已返回、结果正在回报时到达：它不能中断下一次执行。
        dispatcher = new JobDispatcher(registry, logs, result -> {
            results.add(result);
            if (result.logId() == first.logId()) {
                current.get().run(cover);
            }
        }, JobTracing.NONE, Duration.ofSeconds(90));
        current.set(dispatcher);
        dispatcher.run(first);
        assertThat(resultFor(first).handleCode()).isEqualTo(Protocol.SUCCESS);
        awaitStarted();
        jobs.release.release();
        assertThat(resultFor(cover).handleCode()).isEqualTo(Protocol.SUCCESS);
    }

    @Test
    void aFailureOutsideTheMethodIsReportedAndTheWorkerGoesOn() {
        dispatcher.shutdown(Duration.ofSeconds(1));
        AtomicBoolean broken = new AtomicBoolean(true);
        dispatcher = dispatcher(Duration.ofSeconds(90), (handler, jobId, logId) -> {
            if (broken.getAndSet(false)) {
                throw new IllegalStateException("tracing unavailable");
            }
            return JobTracing.NONE.start(handler, jobId, logId);
        });
        Protocol.TriggerRequest first = trigger(17, "quick");
        dispatcher.run(first);
        assertThat(resultFor(first)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.FAIL, "执行器内部错误：java.lang.IllegalStateException");
        Protocol.TriggerRequest second = trigger(17, "quick");
        assertThat(dispatcher.run(second).succeeded()).isTrue();
        assertThat(resultFor(second).handleCode()).isEqualTo(Protocol.SUCCESS);
    }

    @Test
    void aFailureAfterTheMethodSucceededKeepsTheSuccess() {
        dispatcher.shutdown(Duration.ofSeconds(1));
        dispatcher = dispatcher(Duration.ofSeconds(90), (handler, jobId, logId) -> new JobTracing.Scope() {
            @Override public String traceId() { return null; }
            @Override public void error(Throwable failure) { }
            @Override public void close() { throw new IllegalStateException("span end failed"); }
        });
        Protocol.TriggerRequest trigger = trigger(19, "quick");
        dispatcher.run(trigger);
        assertThat(resultFor(trigger)).extracting(Protocol.CallbackRequest::handleCode, Protocol.CallbackRequest::handleMsg)
                .containsExactly(Protocol.SUCCESS, null);
        assertThat(jobs.calls).hasSize(1);
    }

    @Test
    void aJobRunsWithoutTheCallerIdentityOfTheThreadThatSubmittedIt() {
        Protocol.TriggerRequest trigger = trigger(18, "identity");
        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(new CallerContext("user-1", "portal", "default"))) {
            dispatcher.run(trigger);
            resultFor(trigger);
        }
        assertThat(jobs.calls).containsExactly("identity:false");
    }

    @Test
    void anIdleWorkerExitsAndIsRecreatedOnTheNextTrigger() {
        dispatcher.shutdown(Duration.ofSeconds(1));
        dispatcher = dispatcher(Duration.ofMillis(200), JobTracing.NONE);
        Protocol.TriggerRequest first = trigger(11, "quick");
        dispatcher.run(first);
        resultFor(first);
        await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().equals("mars-job-11")));
        Protocol.TriggerRequest second = trigger(11, "quick");
        assertThat(dispatcher.run(second).succeeded()).isTrue();
        assertThat(resultFor(second).handleCode()).isEqualTo(Protocol.SUCCESS);
    }

    @Test
    void shutdownCancelsQueuedTriggersInterruptsTheRestAndRefusesNewOnes() throws Exception {
        Protocol.TriggerRequest running = trigger(12, "blocking");
        Protocol.TriggerRequest queued = trigger(12, "blocking");
        dispatcher.run(running);
        awaitStarted();
        dispatcher.run(queued);
        dispatcher.shutdown(Duration.ofMillis(300));
        assertThat(resultFor(queued).handleMsg()).isEqualTo(JobDispatcher.SHUTDOWN_WHILE_QUEUED);
        assertThat(resultFor(running).handleMsg()).isEqualTo(JobDispatcher.SHUTDOWN_INTERRUPTED);
        assertThat(dispatcher.run(trigger(12, "quick")).msg()).isEqualTo("执行器正在关闭，不再接受触发");
    }

    @Test
    void eachRunHasItsOwnTraceAndFailuresAreMarkedOnIt() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        dispatcher.shutdown(Duration.ofSeconds(1));
        dispatcher = dispatcher(Duration.ofSeconds(90), (handler, jobId, logId) -> new JobTracing.Scope() {
            @Override public String traceId() { return "trace-" + logId; }
            @Override public void error(Throwable failure) { events.add("error:" + failure.getMessage()); }
            @Override public void close() { events.add("close:" + handler); }
        });
        Protocol.TriggerRequest failing = trigger(13, "failing");
        dispatcher.run(failing);
        resultFor(failing);
        assertThat(logOf(failing)).contains("开始执行任务 failing，traceId=trace-" + failing.logId());
        assertThat(events).containsExactly("error:boom", "close:failing");
    }
}
