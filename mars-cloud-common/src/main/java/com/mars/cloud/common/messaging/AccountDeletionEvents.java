package com.mars.cloud.common.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.Objects;

/**
 * 账号注销与服务数据清理结果的消息契约。载荷通过 {@link EventEnvelope} 发送。
 *
 * <p>提供载荷结构及显式消息一致性校验；broker 来源认证、事务及幂等处理由应用负责。
 */
public final class AccountDeletionEvents {

    /** 不带运行环境前缀的主题。 */
    public static final String TOPIC = "account-event";
    /** 账号已注销的事件名及 tag。 */
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    /** 一个参与方已完成数据清理的事件名及 tag。 */
    public static final String ACCOUNT_DATA_DELETED = "ACCOUNT_DATA_DELETED";
    /** 当前载荷版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** auth 专用的账号注销事件生产应用。 */
    public static final String AUTH_PRODUCER = "mars-cloud-auth-service";
    /** notice 独占发布的清理结果主题，不带运行环境前缀。 */
    public static final String NOTICE_RESULT_TOPIC = "account-data-deleted-notice";
    /** UPMS 独占发布的清理结果主题，不带运行环境前缀。 */
    public static final String UPMS_RESULT_TOPIC = "account-data-deleted-upms";
    /** lingai 独占发布的清理结果主题，不带运行环境前缀。 */
    public static final String LINGAI_RESULT_TOPIC = "account-data-deleted-lingai";

    private AccountDeletionEvents() {
    }

    /** 数据清理参与方，JSON 使用固定的小写标识。 */
    public enum Participant {
        NOTICE("notice", NOTICE_RESULT_TOPIC, "mars-cloud-notice-service"),
        UPMS("upms", UPMS_RESULT_TOPIC, "mars-cloud-upms-service"),
        LINGAI("lingai", LINGAI_RESULT_TOPIC, "mars-cloud-lingai-service");

        private final String value;

        private final String resultTopic;
        private final String producer;

        Participant(String value, String resultTopic, String producer) {
            this.value = value;
            this.resultTopic = resultTopic;
            this.producer = producer;
        }

        /** 不带运行环境前缀的清理结果主题。 */
        public String resultTopic() {
            return resultTopic;
        }

        /** 本参与方对应的固定生产应用名。 */
        public String producer() {
            return producer;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Participant fromValue(String value) {
            for (Participant participant : values()) {
                if (participant.value.equals(value)) {
                    return participant;
                }
            }
            throw new IllegalArgumentException("participant 必须是 notice、upms 或 lingai");
        }
    }

    /**
     * 账号注销事实。用户编号以字符串传输，避免数值精度丢失。
     *
     * @param schemaVersion 载荷版本，当前只接受 1
     * @param requestId 注销请求编号
     * @param userId 原账号编号
     * @param deletedAt 账号注销时间
     */
    public record AccountDeleted(
            @JsonProperty(value = "schema_version", required = true)
            @JsonDeserialize(using = SchemaVersionDeserializer.class) int schemaVersion,
            @JsonProperty(value = "request_id", required = true) String requestId,
            @JsonProperty(value = "user_id", required = true) String userId,
            @JsonProperty(value = "deleted_at", required = true) Instant deletedAt) {

        @JsonCreator
        public AccountDeleted {
            requireVersion(schemaVersion);
            requestId = requireText(requestId, "requestId");
            userId = requireText(userId, "userId");
            Objects.requireNonNull(deletedAt, "deletedAt 不能为空");
        }

        /** 返回信封与消息使用的业务键。 */
        public String businessKey() {
            return requestId;
        }
    }

    /**
     * 一个参与方的数据清理结果，不表示其他参与方已完成。
     *
     * @param schemaVersion 载荷版本，当前只接受 1
     * @param requestId 原注销请求编号
     * @param userId 原账号编号，以字符串传输
     * @param deletedAt 原账号注销时间
     * @param participant 完成清理的参与方
     * @param completedAt 该参与方的清理完成时间
     */
    public record AccountDataDeleted(
            @JsonProperty(value = "schema_version", required = true)
            @JsonDeserialize(using = SchemaVersionDeserializer.class) int schemaVersion,
            @JsonProperty(value = "request_id", required = true) String requestId,
            @JsonProperty(value = "user_id", required = true) String userId,
            @JsonProperty(value = "deleted_at", required = true) Instant deletedAt,
            @JsonProperty(value = "participant", required = true) Participant participant,
            @JsonProperty(value = "completed_at", required = true) Instant completedAt) {

        @JsonCreator
        public AccountDataDeleted {
            requireVersion(schemaVersion);
            requestId = requireText(requestId, "requestId");
            userId = requireText(userId, "userId");
            Objects.requireNonNull(deletedAt, "deletedAt 不能为空");
            Objects.requireNonNull(participant, "participant 不能为空");
            Objects.requireNonNull(completedAt, "completedAt 不能为空");
        }

        /** 返回按请求编号和参与方组成的业务键。 */
        public String businessKey() {
            return requestId + ":" + participant.value();
        }
    }

    /** 判断不带运行环境前缀的主题是否是三个固定的清理结果主题之一。 */
    public static boolean isResultTopic(String topic) {
        return NOTICE_RESULT_TOPIC.equals(topic) || UPMS_RESULT_TOPIC.equals(topic) || LINGAI_RESULT_TOPIC.equals(topic);
    }

    /**
     * 校验账号注销消息的一致性并返回类型确定的载荷。
     *
     * <p>topic、tag、key 必须来自实际收到的消息，不能从信封复制。prefix 来自应用配置。
     * 此校验不代替 broker 的应用身份及主题发布权限，也不核实注销业务事实。
     *
     * @param prefix 配置的运行环境前缀，无前缀时传空字符串
     * @param topic 实际收到的完整主题名
     * @param tag 实际收到的 tag
     * @param key 实际收到的业务键
     * @param event 已反序列化载荷的信封
     * @return 账号注销载荷
     */
    public static AccountDeleted validateDeleted(String prefix, String topic, String tag, String key, EventEnvelope<?> event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (!(event.payload() instanceof AccountDeleted payload)) {
            throw new IllegalArgumentException("payload 必须是 AccountDeleted");
        }
        validateDelivery(prefix, TOPIC, ACCOUNT_DELETED, AUTH_PRODUCER, payload.businessKey(), topic, tag, key, event);
        return payload;
    }

    /**
     * 校验参与方结果消息的一致性并返回类型确定的载荷。
     *
     * <p>主题与生产应用按载荷参与方的固定映射校验，拒绝通过共用账号主题传送结果。
     * topic、tag、key 必须来自实际消息；应用仍须用 broker 独立身份限制发布权限。
     *
     * @param prefix 配置的运行环境前缀，无前缀时传空字符串
     * @param topic 实际收到的完整主题名
     * @param tag 实际收到的 tag
     * @param key 实际收到的业务键
     * @param event 已反序列化载荷的信封
     * @return 参与方清理结果载荷
     */
    public static AccountDataDeleted validateResult(String prefix, String topic, String tag, String key, EventEnvelope<?> event) {
        Objects.requireNonNull(event, "event 不能为空");
        if (!(event.payload() instanceof AccountDataDeleted payload)) {
            throw new IllegalArgumentException("payload 必须是 AccountDataDeleted");
        }
        Participant participant = payload.participant();
        validateDelivery(prefix, participant.resultTopic(), ACCOUNT_DATA_DELETED, participant.producer(),
                payload.businessKey(), topic, tag, key, event);
        return payload;
    }

    private static void validateDelivery(String prefix, String rawTopic, String type, String producer, String businessKey,
                                         String topic, String tag, String key, EventEnvelope<?> event) {
        if (!MessagingNames.withPrefix(prefix, rawTopic).equals(topic)
                || !type.equals(tag) || !type.equals(event.eventType())
                || !businessKey.equals(key) || !businessKey.equals(event.key())
                || !producer.equals(event.producer())) {
            throw new IllegalArgumentException("账号注销消息的主题、事件类型、业务键或生产应用不一致");
        }
    }

    /** 仅供本契约的版本字段使用，拒绝小数及字符串到整数的隐式转换。 */
    public static final class SchemaVersionDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "schema_version 必须是 JSON 整数");
            }
            return parser.getIntValue();
        }
    }

    private static void requireVersion(int version) {
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的账号注销载荷版本");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
        return value;
    }
}
