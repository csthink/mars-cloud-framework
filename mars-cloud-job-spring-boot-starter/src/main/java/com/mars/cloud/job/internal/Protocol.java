package com.mars.cloud.job.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * xxl-job-admin 3.4.2 与执行器之间的 HTTP 协议：请求体、响应体与它们的 JSON 编解码。
 *
 * <p>字段名按调度中心的实现逐字对应，其中 {@code logDateTim} 少一个字母是协议原样，不能改正。
 * 编解码用组件自己的 {@link JsonMapper}，应用对 ObjectMapper 的定制（命名策略、空值处理）不影响协议。
 * 调度中心增加的字段被忽略；缺少的数值字段取 0。
 */
public final class Protocol {

    /** 调度中心与执行器之间的访问令牌请求头。 */
    public static final String ACCESS_TOKEN_HEADER = "XXL-JOB-ACCESS-TOKEN";
    /** 执行器注册时的注册分组。 */
    public static final String EXECUTOR_GROUP = "EXECUTOR";

    public static final int SUCCESS = 200;
    public static final int FAIL = 500;
    public static final int TIMEOUT = 502;

    /** 只支持的任务类型：调用执行器里登记的 Java 方法。 */
    public static final String BEAN = "BEAN";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private Protocol() {
    }

    /** 调度中心触发一次执行（{@code POST /run}）。 */
    public record TriggerRequest(int jobId, String executorHandler, String executorParams, String executorBlockStrategy,
                                 int executorTimeout, long logId, long logDateTime, String glueType, String glueSource,
                                 long glueUpdatetime, int broadcastIndex, int broadcastTotal) {
    }

    /** 调度中心询问任务是否空闲（{@code POST /idleBeat}），用于忙碌转移路由。 */
    public record IdleBeatRequest(int jobId) {
    }

    /** 调度中心终止任务（{@code POST /kill}）。 */
    public record KillRequest(int jobId) {
    }

    /** 调度中心读取执行日志（{@code POST /log}），{@code fromLineNum} 从 1 开始。 */
    public record LogRequest(long logDateTim, long logId, int fromLineNum) {
    }

    /** 执行日志的一段；调度中心在 {@code fromLineNum > toLineNum} 且执行已有结果时停止读取。 */
    public record LogResult(int fromLineNum, int toLineNum, String logContent, @JsonProperty("isEnd") boolean isEnd) {
    }

    /** 执行器向调度中心注册或摘除（{@code POST /api/registry}、{@code /api/registryRemove}）。 */
    public record RegistryRequest(String registryGroup, String registryKey, String registryValue) {
    }

    /** 执行器回报一次执行的结果（{@code POST /api/callback}，请求体是它的数组）。 */
    public record CallbackRequest(long logId, long logDateTim, int handleCode, String handleMsg) {
    }

    /** 双方共用的响应体；{@code code == 200} 表示成功。 */
    public record Response(int code, String msg, Object data) {

        public static Response success() {
            return new Response(SUCCESS, null, null);
        }

        public static Response success(Object data) {
            return new Response(SUCCESS, null, data);
        }

        public static Response fail(String message) {
            return new Response(FAIL, message, null);
        }

        public boolean succeeded() {
            return code == SUCCESS;
        }
    }

    public static byte[] write(Object value) {
        return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @throws IllegalArgumentException 请求体不是这个结构的 JSON
     */
    public static <T> T read(byte[] body, Class<T> type) {
        try {
            T value = MAPPER.readValue(body, type);
            if (value == null) {
                throw new IllegalArgumentException("请求体为空");
            }
            return value;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("请求体无法解析为 " + type.getSimpleName() + "：" + e.getOriginalMessage(), e);
        }
    }

    static List<CallbackRequest> readCallbacks(byte[] body) {
        try {
            return MAPPER.readValue(body, new TypeReference<List<CallbackRequest>>() { });
        } catch (JacksonException e) {
            throw new IllegalArgumentException("请求体无法解析为回调数组：" + e.getOriginalMessage(), e);
        }
    }
}
