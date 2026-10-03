package com.mars.cloud.common.messaging;

import com.mars.cloud.common.messaging.AccountDeletionEvents.AccountDataDeleted;
import com.mars.cloud.common.messaging.AccountDeletionEvents.AccountDeleted;
import com.mars.cloud.common.messaging.AccountDeletionEvents.Participant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionDeliveryTest {
    private static final Instant TIME = Instant.parse("2026-10-03T00:00:00Z");
    private static final AccountDeleted DELETED = new AccountDeleted(1, "request-42", "9223372036854775807", TIME);

    @ParameterizedTest
    @EnumSource(Participant.class)
    void fixedMappingsAndDeliveryWorkWithAndWithoutPrefix(Participant participant) {
        String topic = "account-data-deleted-" + participant.value();
        String producer = "mars-cloud-" + participant.value() + "-service";
        assertThat(participant.resultTopic()).isEqualTo(topic);
        assertThat(participant.producer()).isEqualTo(producer);
        assertThat(AccountDeletionEvents.isResultTopic(topic)).isTrue();
        assertThat(MessagingNames.requireTopic(topic)).isEqualTo(topic);
        String group = MessagingNames.consumerGroup("mars-cloud-auth-service", topic);
        assertThat(group).isEqualTo("mars-cloud-auth-service-" + topic);
        AccountDataDeleted payload = result(participant);
        EventEnvelope<?> event = envelope("ACCOUNT_DATA_DELETED", producer, payload.businessKey(), payload);
        for (String prefix : new String[]{"", "s5-"}) {
            assertThat(AccountDeletionEvents.validateResult(prefix, prefix + topic, "ACCOUNT_DATA_DELETED", payload.businessKey(), event))
                    .isSameAs(payload);
            assertThat(MessagingNames.withPrefix(prefix, group)).isEqualTo(prefix + group);
        }
        for (Participant other : Participant.values()) {
            if (other != participant) {
                rejectsResult(other.resultTopic(), "ACCOUNT_DATA_DELETED", payload.businessKey(), event);
                rejectsResult(topic, "ACCOUNT_DATA_DELETED", payload.businessKey(),
                        envelope("ACCOUNT_DATA_DELETED", other.producer(), payload.businessKey(), payload));
            }
        }
    }

    @Test
    void deletionDeliveryRequiresAuthAndAccountTopic() {
        EventEnvelope<?> event = envelope("ACCOUNT_DELETED", "mars-cloud-auth-service", DELETED.businessKey(), DELETED);
        for (String prefix : new String[]{"", "s5-"}) {
            assertThat(AccountDeletionEvents.validateDeleted(prefix, prefix + "account-event", "ACCOUNT_DELETED", "request-42", event))
                    .isSameAs(DELETED);
        }
        for (String topic : new String[]{"account-data-deleted-notice", "s5-account-event", "order-event"}) {
            rejectsDeleted(topic, "ACCOUNT_DELETED", "request-42", event);
        }
        rejectsDeleted("account-event", "ACCOUNT_DATA_DELETED", "request-42", event);
        rejectsDeleted("account-event", "ACCOUNT_DELETED", "other", event);
        rejectsDeleted("account-event", "ACCOUNT_DELETED", "request-42", envelope("OTHER", "mars-cloud-auth-service", "request-42", DELETED));
        rejectsDeleted("account-event", "ACCOUNT_DELETED", "request-42", envelope("ACCOUNT_DELETED", "mars-cloud-notice-service", "request-42", DELETED));
        rejectsDeleted("account-event", "ACCOUNT_DELETED", "request-42", envelope("ACCOUNT_DELETED", "mars-cloud-auth-service", "other", DELETED));
    }

    @Test
    void resultsRejectOldTopicAndInconsistentTransportOrEnvelope() {
        AccountDataDeleted payload = result(Participant.NOTICE);
        EventEnvelope<?> event = envelope("ACCOUNT_DATA_DELETED", Participant.NOTICE.producer(), payload.businessKey(), payload);
        for (String topic : new String[]{"account-event", "account-data-deleted-upms", "s5-account-data-deleted-notice", "other-event"}) {
            rejectsResult(topic, "ACCOUNT_DATA_DELETED", payload.businessKey(), event);
        }
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DELETED", payload.businessKey(), event);
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", "request-42:upms", event);
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", payload.businessKey(),
                envelope("OTHER", Participant.NOTICE.producer(), payload.businessKey(), payload));
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", payload.businessKey(),
                envelope("ACCOUNT_DATA_DELETED", Participant.NOTICE.producer(), "other", payload));
        assertThatThrownBy(() -> AccountDeletionEvents.validateResult("s5-", "s6-" + Participant.NOTICE.resultTopic(),
                "ACCOUNT_DATA_DELETED", payload.businessKey(), event)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown", "account-data-deleted-support", "account-data-deleted-notice-extra", "s5-account-data-deleted-notice", "ACCOUNT-DATA-DELETED-NOTICE"})
    void noOtherUnsuffixedTopicsAreAllowed(String topic) {
        assertThat(AccountDeletionEvents.isResultTopic(topic)).isFalse();
        assertThat(MessagingNames.isTopic(topic)).isFalse();
        assertThatThrownBy(() -> MessagingNames.requireTopic(topic)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "wrong"})
    void missingOrWrongTransportMetadataIsRejected(String invalid) {
        EventEnvelope<?> deleted = envelope("ACCOUNT_DELETED", "mars-cloud-auth-service", DELETED.businessKey(), DELETED);
        AccountDataDeleted payload = result(Participant.NOTICE);
        EventEnvelope<?> result = envelope("ACCOUNT_DATA_DELETED", Participant.NOTICE.producer(), payload.businessKey(), payload);
        rejectsDeleted(invalid, "ACCOUNT_DELETED", "request-42", deleted);
        rejectsDeleted("account-event", invalid, "request-42", deleted);
        rejectsDeleted("account-event", "ACCOUNT_DELETED", invalid, deleted);
        rejectsResult(invalid, "ACCOUNT_DATA_DELETED", payload.businessKey(), result);
        rejectsResult(Participant.NOTICE.resultTopic(), invalid, payload.businessKey(), result);
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", invalid, result);
    }

    @Test
    void nullAndWrongPayloadTypesAreRejected() {
        rejectsDeleted("account-event", "ACCOUNT_DELETED", "request-42", null);
        rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", "request-42:notice", null);
        for (Object payload : new Object[]{null, "value", Map.of("request_id", "request-42"), result(Participant.NOTICE)}) {
            rejectsDeleted("account-event", "ACCOUNT_DELETED", "request-42", envelope("ACCOUNT_DELETED", "mars-cloud-auth-service", "request-42", payload));
        }
        for (Object payload : new Object[]{null, "value", Map.of("participant", "notice"), DELETED}) {
            rejectsResult(Participant.NOTICE.resultTopic(), "ACCOUNT_DATA_DELETED", "request-42:notice",
                    envelope("ACCOUNT_DATA_DELETED", Participant.NOTICE.producer(), "request-42:notice", payload));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"S5-", "s5", "s5--"})
    void invalidConfiguredPrefixesFail(String prefix) {
        AccountDataDeleted payload = result(Participant.NOTICE);
        assertThatThrownBy(() -> AccountDeletionEvents.validateResult(prefix, prefix + Participant.NOTICE.resultTopic(),
                "ACCOUNT_DATA_DELETED", payload.businessKey(), envelope("ACCOUNT_DATA_DELETED", Participant.NOTICE.producer(), payload.businessKey(), payload)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EventEnvelope<?> envelope(String type, String producer, String key, Object payload) {
        return new EventEnvelope<>("event-42", type, TIME, producer, null, key, payload);
    }

    private static AccountDataDeleted result(Participant participant) {
        return new AccountDataDeleted(1, "request-42", "9223372036854775807", TIME, participant, TIME.plusSeconds(2));
    }

    private static void rejectsDeleted(String topic, String tag, String key, EventEnvelope<?> event) {
        assertThatThrownBy(() -> AccountDeletionEvents.validateDeleted("", topic, tag, key, event)).isInstanceOf(RuntimeException.class);
    }

    private static void rejectsResult(String topic, String tag, String key, EventEnvelope<?> event) {
        assertThatThrownBy(() -> AccountDeletionEvents.validateResult("", topic, tag, key, event)).isInstanceOf(RuntimeException.class);
    }
}
