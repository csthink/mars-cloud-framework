package com.mars.cloud.common.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.Objects;

/**
 * 账号注销与服务数据清理结果的消息契约。载荷通过 {@link EventEnvelope} 发送。
 *
 * <p>这里只校验载荷结构；事件来源、业务键与信封的一致性、事务及幂等处理由应用负责。
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

    private AccountDeletionEvents() {
    }

    /** 数据清理参与方，JSON 使用固定的小写标识。 */
    public enum Participant {
        NOTICE("notice"), UPMS("upms"), LINGAI("lingai");

        private final String value;

        Participant(String value) {
            this.value = value;
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
            @JsonProperty(value = "schema_version", required = true) int schemaVersion,
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
            @JsonProperty(value = "schema_version", required = true) int schemaVersion,
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
