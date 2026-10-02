package com.mars.cloud.common.messaging;

import com.mars.cloud.common.messaging.AccountDeletionEvents.AccountDataDeleted;
import com.mars.cloud.common.messaging.AccountDeletionEvents.AccountDeleted;
import com.mars.cloud.common.messaging.AccountDeletionEvents.Participant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionEventsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant DELETED_AT = Instant.parse("2026-10-02T12:34:56.123Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-02T12:35:00Z");
    private static final String REQUEST_ID = "request-42";
    private static final String USER_ID = "9223372036854775807";

    @Test
    void namesRemainCompatibleWithMessagingConventions() {
        assertThat(AccountDeletionEvents.SCHEMA_VERSION).isEqualTo(1);
        assertThat(AccountDeletionEvents.TOPIC).isEqualTo(MessagingNames.topic("account"));
        assertThat(AccountDeletionEvents.ACCOUNT_DELETED).isEqualTo("ACCOUNT_DELETED");
        assertThat(AccountDeletionEvents.ACCOUNT_DATA_DELETED).isEqualTo("ACCOUNT_DATA_DELETED");
        assertThat(MessagingNames.isTag(AccountDeletionEvents.ACCOUNT_DELETED)).isTrue();
        assertThat(MessagingNames.isTag(AccountDeletionEvents.ACCOUNT_DATA_DELETED)).isTrue();
        assertThat(MessagingNames.withPrefix("s5-", AccountDeletionEvents.TOPIC)).isEqualTo("s5-account-event");
    }

    @Test
    void accountDeletedHasOnlyTheVersionedFieldsAndRoundTripsInsideTheEnvelope() {
        AccountDeleted payload = deleted();
        EventEnvelope<AccountDeleted> event = EventEnvelope.of(AccountDeletionEvents.ACCOUNT_DELETED,
                "mars-cloud-auth-service", payload.businessKey(), payload);
        String serialized = MAPPER.writeValueAsString(event);
        JsonNode json = MAPPER.readTree(serialized).get("payload");
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("schema_version", "request_id", "user_id", "deleted_at");
        assertCommonFields(json);
        assertThat(event.key()).isEqualTo(REQUEST_ID);
        assertThat(MAPPER.readValue(serialized, new TypeReference<EventEnvelope<AccountDeleted>>() { })).isEqualTo(event);
    }

    @ParameterizedTest
    @EnumSource(Participant.class)
    void completionHasOnlyTheVersionedFieldsAndRoundTripsForEachParticipant(Participant participant) {
        AccountDataDeleted payload = completed(participant);
        EventEnvelope<AccountDataDeleted> event = EventEnvelope.of(AccountDeletionEvents.ACCOUNT_DATA_DELETED,
                "mars-cloud-" + participant.value() + "-service", payload.businessKey(), payload);
        String serialized = MAPPER.writeValueAsString(event);
        JsonNode json = MAPPER.readTree(serialized).get("payload");
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "schema_version", "request_id", "user_id", "deleted_at", "participant", "completed_at");
        assertCommonFields(json);
        assertThat(json.get("participant").asString()).isEqualTo(participant.value());
        assertThat(json.get("completed_at").asString()).isEqualTo("2026-10-02T12:35:00Z");
        assertThat(event.key()).isEqualTo(REQUEST_ID + ":" + participant.value());
        assertThat(MAPPER.readValue(serialized, new TypeReference<EventEnvelope<AccountDataDeleted>>() { })).isEqualTo(event);
    }

    @Test
    void participantWireValuesAreStable() {
        assertThat(Participant.values()).extracting(Participant::value).containsExactly("notice", "upms", "lingai");
        assertThat(completed(Participant.NOTICE).businessKey()).isNotEqualTo(completed(Participant.UPMS).businessKey());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unknownVersionsAreRejectedInConstructorsAndJson(int version) {
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountDeleted(version, REQUEST_ID, USER_ID, DELETED_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AccountDataDeleted(version, REQUEST_ID, USER_ID, DELETED_AT, Participant.NOTICE, COMPLETED_AT));
        rejectField(deleted(), AccountDeleted.class, "schema_version", version);
        rejectField(completed(Participant.NOTICE), AccountDataDeleted.class, "schema_version", version);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blankIdentifiersAreRejectedInConstructorsAndJson(String value) {
        assertThatThrownBy(() -> new AccountDeleted(1, value, USER_ID, DELETED_AT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AccountDeleted(1, REQUEST_ID, value, DELETED_AT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AccountDataDeleted(1, value, USER_ID, DELETED_AT, Participant.NOTICE, COMPLETED_AT))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new AccountDataDeleted(1, REQUEST_ID, value, DELETED_AT, Participant.NOTICE, COMPLETED_AT))
                .isInstanceOf(RuntimeException.class);
        for (String field : new String[]{"request_id", "user_id"}) {
            rejectField(deleted(), AccountDeleted.class, field, value);
            rejectField(completed(Participant.NOTICE), AccountDataDeleted.class, field, value);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"NOTICE", "unknown", " notice", "notice "})
    void unknownParticipantsAreRejected(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> Participant.fromValue(value));
        rejectField(completed(Participant.NOTICE), AccountDataDeleted.class, "participant", value);
    }

    @Test
    void nullTimesAndParticipantAreRejectedByConstructors() {
        assertThatNullPointerException().isThrownBy(() -> new AccountDeleted(1, REQUEST_ID, USER_ID, null));
        assertThatNullPointerException().isThrownBy(() -> new AccountDataDeleted(1, REQUEST_ID, USER_ID, null, Participant.NOTICE, COMPLETED_AT));
        assertThatNullPointerException().isThrownBy(() -> new AccountDataDeleted(1, REQUEST_ID, USER_ID, DELETED_AT, null, COMPLETED_AT));
        assertThatNullPointerException().isThrownBy(() -> new AccountDataDeleted(1, REQUEST_ID, USER_ID, DELETED_AT, Participant.NOTICE, null));
    }

    @Test
    void missingAndNullJsonFieldsAreRejected() {
        assertRequiredFields(deleted(), AccountDeleted.class);
        assertRequiredFields(completed(Participant.NOTICE), AccountDataDeleted.class);
    }

    private static void assertCommonFields(JsonNode json) {
        assertThat(json.get("schema_version").asInt()).isEqualTo(1);
        assertThat(json.get("request_id").asString()).isEqualTo(REQUEST_ID);
        assertThat(json.get("user_id").isString()).isTrue();
        assertThat(json.get("user_id").asString()).isEqualTo(USER_ID);
        assertThat(json.get("deleted_at").asString()).isEqualTo("2026-10-02T12:34:56.123Z");
    }

    private static <T> void assertRequiredFields(T payload, Class<T> type) {
        JsonNode original = MAPPER.valueToTree(payload);
        for (String field : original.propertyNames()) {
            ObjectNode missing = (ObjectNode) original.deepCopy();
            missing.remove(field);
            assertThatThrownBy(() -> MAPPER.treeToValue(missing, type)).as("missing %s", field).isInstanceOf(RuntimeException.class);
            rejectField(payload, type, field, null);
        }
    }

    private static <T> void rejectField(T payload, Class<T> type, String field, Object value) {
        ObjectNode invalid = MAPPER.valueToTree(payload);
        invalid.set(field, MAPPER.valueToTree(value));
        assertThatThrownBy(() -> MAPPER.treeToValue(invalid, type)).as("invalid %s", field).isInstanceOf(RuntimeException.class);
    }

    private static AccountDeleted deleted() {
        return new AccountDeleted(1, REQUEST_ID, USER_ID, DELETED_AT);
    }

    private static AccountDataDeleted completed(Participant participant) {
        return new AccountDataDeleted(1, REQUEST_ID, USER_ID, DELETED_AT, participant, COMPLETED_AT);
    }
}
