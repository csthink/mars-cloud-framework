package com.mars.cloud.job.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协议字段名必须与 xxl-job-admin 3.4.2 逐字一致：调度中心按这些名字读写 JSON。
 */
class ProtocolTest {

    private static String json(Object value) {
        return new String(Protocol.write(value), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void logResultUsesTheFieldNamesTheSchedulerReads() {
        assertThat(json(new Protocol.LogResult(3, 5, "line", true)))
                .isEqualTo("{\"fromLineNum\":3,\"toLineNum\":5,\"logContent\":\"line\",\"isEnd\":true}");
    }

    @Test
    void callbackKeepsTheMisspelledDateField() {
        assertThat(json(new Protocol.CallbackRequest(7, 1700000000000L, 500, "boom")))
                .isEqualTo("{\"logId\":7,\"logDateTim\":1700000000000,\"handleCode\":500,\"handleMsg\":\"boom\"}");
    }

    @Test
    void registryUsesTheExecutorGroup() {
        assertThat(json(new Protocol.RegistryRequest(Protocol.EXECUTOR_GROUP, "s1-sample-app", "http://127.0.0.1:10203/")))
                .isEqualTo("{\"registryGroup\":\"EXECUTOR\",\"registryKey\":\"s1-sample-app\",\"registryValue\":\"http://127.0.0.1:10203/\"}");
    }

    @Test
    void triggerFromTheSchedulerIgnoresUnknownFieldsAndDefaultsMissingNumbers() {
        Protocol.TriggerRequest trigger = Protocol.read(bytes("""
                {"jobId":12,"executorHandler":"sampleHeartbeat","executorParams":"p","executorBlockStrategy":"SERIAL_EXECUTION",
                 "logId":99,"logDateTime":1700000000000,"glueType":"BEAN","glueSource":null,"glueUpdatetime":1,
                 "broadcastIndex":0,"broadcastTotal":1,"addedInALaterVersion":"x"}
                """), Protocol.TriggerRequest.class);
        assertThat(trigger.jobId()).isEqualTo(12);
        assertThat(trigger.executorHandler()).isEqualTo("sampleHeartbeat");
        assertThat(trigger.executorTimeout()).isZero();
        assertThat(trigger.logId()).isEqualTo(99);
        assertThat(trigger.broadcastTotal()).isEqualTo(1);
    }

    @Test
    void logRequestReadsTheMisspelledDateField() {
        Protocol.LogRequest request = Protocol.read(bytes("{\"logDateTim\":1700000000000,\"logId\":5,\"fromLineNum\":1}"),
                Protocol.LogRequest.class);
        assertThat(request.logDateTim()).isEqualTo(1700000000000L);
        assertThat(request.fromLineNum()).isEqualTo(1);
    }

    @Test
    void responsesFollowTheCodeConvention() {
        assertThat(json(Protocol.Response.success())).isEqualTo("{\"code\":200,\"msg\":null,\"data\":null}");
        assertThat(Protocol.read(bytes("{\"code\":500,\"msg\":\"x\"}"), Protocol.Response.class).succeeded()).isFalse();
        assertThat(Protocol.read(bytes("{\"code\":200,\"msg\":null,\"data\":null}"), Protocol.Response.class).succeeded()).isTrue();
    }

    @Test
    void malformedBodiesAreRejectedWithAReadableMessage() {
        assertThatThrownBy(() -> Protocol.read(bytes("not json"), Protocol.TriggerRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TriggerRequest");
        assertThatThrownBy(() -> Protocol.read(bytes(""), Protocol.KillRequest.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
