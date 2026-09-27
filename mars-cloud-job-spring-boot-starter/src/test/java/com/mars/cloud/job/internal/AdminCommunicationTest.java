package com.mars.cloud.job.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 执行器调用调度中心的三个接口：注册心跳、摘除与回调。
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCommunicationTest {

    static final String TOKEN_VALUE = "test-access-token-0123456789";
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeAdmin admin;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() throws Exception {
        admin = new FakeAdmin();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        admin.close();
    }

    /** 一个没有进程监听的本机端口：连接立即被拒绝。 */
    private static URI refusedAddress() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    @Test
    void registrationCarriesTheTokenAndTheExecutorAddress() {
        AdminClient client = new AdminClient(List.of(admin.uri()), TOKEN_VALUE, TIMEOUT);
        AdminClient.Outcome outcome = client.registry(
                new Protocol.RegistryRequest(Protocol.EXECUTOR_GROUP, "s1-sample-app", "http://127.0.0.1:10203/"));
        assertThat(outcome.succeeded()).isTrue();
        FakeAdmin.Received received = admin.received("/api/registry").getFirst();
        assertThat(received.token()).isEqualTo(TOKEN_VALUE);
        assertThat(received.body()).isEqualTo(
                "{\"registryGroup\":\"EXECUTOR\",\"registryKey\":\"s1-sample-app\",\"registryValue\":\"http://127.0.0.1:10203/\"}");
    }

    @Test
    void theNextAdminIsTriedWhenOneIsUnreachable() throws Exception {
        URI refused = refusedAddress();
        AdminClient client = new AdminClient(List.of(refused, admin.uri()), TOKEN_VALUE, TIMEOUT);
        assertThat(client.callback(List.of(new Protocol.CallbackRequest(1, 2, 200, null))).succeeded()).isTrue();
        assertThat(admin.callbacks()).containsExactly(new Protocol.CallbackRequest(1, 2, 200, null));
    }

    @Test
    void failuresNameEveryAdminAndItsReason() throws Exception {
        admin.failWith(Protocol.FAIL);
        URI refused = refusedAddress();
        AdminClient.Outcome outcome = new AdminClient(List.of(admin.uri(), refused), TOKEN_VALUE, TIMEOUT)
                .registry(new Protocol.RegistryRequest(Protocol.EXECUTOR_GROUP, "app-a", "http://127.0.0.1:1/"));
        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.detail()).contains(admin.uri() + "/api/registry 返回 code=500 msg=rejected")
                .contains(refused + "/api/registry 调用失败");
        admin.recover();
        admin.httpStatus(404);
        assertThat(new AdminClient(List.of(admin.uri()), TOKEN_VALUE, TIMEOUT)
                .registry(new Protocol.RegistryRequest(Protocol.EXECUTOR_GROUP, "app-a", "http://127.0.0.1:1/")).detail())
                .isEqualTo(admin.uri() + "/api/registry 返回 HTTP 404");
    }

    @Test
    void theHeartbeatWarnsOnceWhileTheAdminIsDownAndNotesTheRecovery(CapturedOutput output) {
        admin.failWith(Protocol.FAIL);
        RegistryHeartbeat heartbeat = new RegistryHeartbeat(new AdminClient(List.of(admin.uri()), TOKEN_VALUE, TIMEOUT),
                "heartbeat-app", "http://127.0.0.1:10203/", scheduler, Duration.ofMillis(50));
        heartbeat.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.received("/api/registry").size() >= 3);
        admin.recover();
        await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains("执行器已在调度中心注册：app=heartbeat-app"));
        heartbeat.stop();
        assertThat(output.getOut().split("执行器向调度中心注册失败", -1)).hasSize(2);
        assertThat(admin.received("/api/registryRemove")).hasSize(1);
        assertThat(output.getOut()).contains("执行器已从调度中心摘除：app=heartbeat-app，address=http://127.0.0.1:10203/");
    }

    @Test
    void removalWaitsForARegistrationInFlightAndNothingIsRegisteredAfterIt() throws Exception {
        admin.holdRegistrations();
        // 间隔取 1 分钟，只有启动时的那一次注册会在用例期间发出。
        RegistryHeartbeat heartbeat = new RegistryHeartbeat(new AdminClient(List.of(admin.uri()), TOKEN_VALUE, TIMEOUT),
                "ordered-app", "http://127.0.0.1:10203/", scheduler, Duration.ofMinutes(1));
        heartbeat.start();
        assertThat(admin.awaitHeldRegistration(Duration.ofSeconds(5))).isTrue();
        Thread stopping = Thread.ofVirtual().start(heartbeat::stop);
        // 摘除与在途的注册不互斥时，摘除会在这段时间里先到达调度中心。
        Thread.sleep(200);
        admin.releaseRegistrations();
        stopping.join(Duration.ofSeconds(5));
        heartbeat.beat();
        assertThat(admin.received().stream().map(FakeAdmin.Received::path).toList())
                .containsExactly("/api/registry", "/api/registryRemove");
    }

    @Test
    void callbacksAreBatchedAndRetriedUntilDelivered(CapturedOutput output) {
        admin.failWith(Protocol.FAIL);
        CallbackSender sender = new CallbackSender(new AdminClient(List.of(admin.uri()), TOKEN_VALUE, TIMEOUT), 100,
                Duration.ofMillis(20));
        // 先入队再启动发送线程，第一批一定是这两条（启动在前时发送线程可能只取到第一条）。
        sender.accept(new Protocol.CallbackRequest(11, 1, 200, null));
        sender.accept(new Protocol.CallbackRequest(12, 1, 500, "boom"));
        sender.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.received("/api/callback").size() >= 2);
        admin.recover();
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.callbacks().stream().filter(r -> r.logId() == 12).count() >= 3);
        sender.stop(Duration.ofSeconds(1));
        assertThat(output.getOut()).contains("执行结果回报调度中心失败，按退避重发：2 条").contains("执行结果回报恢复");
        assertThat(output.getOut()).doesNotContain("未送达调度中心");
    }

    @Test
    void aFullQueueDropsTheOldestResultAndShutdownReportsWhatWasNotDelivered(CapturedOutput output) throws Exception {
        CallbackSender sender = new CallbackSender(new AdminClient(List.of(refusedAddress()), TOKEN_VALUE, TIMEOUT), 2,
                Duration.ofMillis(20));
        sender.accept(new Protocol.CallbackRequest(21, 1, 200, null));
        sender.accept(new Protocol.CallbackRequest(22, 1, 200, null));
        sender.accept(new Protocol.CallbackRequest(23, 1, 200, null));
        assertThat(output.getOut()).contains("回调队列已满，丢弃最旧的一条执行结果：logId=21，handleCode=200");
        sender.stop(Duration.ofMillis(100));
        assertThat(output.getOut()).contains("执行器关闭时有 2 条执行结果未送达调度中心");
    }
}
