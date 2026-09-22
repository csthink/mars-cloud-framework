package com.mars.cloud.common.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EventEnvelopeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record OrderPaid(String orderId, long amountCents) {
    }

    @Test
    void serializesWithSnakeCaseFieldNamesAndIsoInstant() throws Exception {
        EventEnvelope<OrderPaid> envelope = new EventEnvelope<>(
                "8f2c1a2e-9c7b-4d51-9e0d-3d9b1a1f6c4a", "PAID", Instant.parse("2026-09-21T10:15:30Z"),
                "mars-cloud-order-service", "0af7651916cd43dd8448eb211c80319c", "order-42",
                new OrderPaid("order-42", 1990));

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(envelope));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "event_id", "event_type", "occurred_at", "producer", "trace_id", "key", "payload");
        assertThat(json.get("event_id").asString()).isEqualTo("8f2c1a2e-9c7b-4d51-9e0d-3d9b1a1f6c4a");
        assertThat(json.get("event_type").asString()).isEqualTo("PAID");
        assertThat(json.get("occurred_at").asString()).isEqualTo("2026-09-21T10:15:30Z");
        assertThat(json.get("producer").asString()).isEqualTo("mars-cloud-order-service");
        assertThat(json.get("trace_id").asString()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(json.get("key").asString()).isEqualTo("order-42");
        assertThat(json.get("payload").get("orderId").asString()).isEqualTo("order-42");
        assertThat(json.get("payload").get("amountCents").asLong()).isEqualTo(1990);
    }

    @Test
    void roundTripsThroughJsonWithAGenericPayload() throws Exception {
        EventEnvelope<Map<String, Object>> original = EventEnvelope.of(
                "GRANTED", "mars-cloud-product-service", "order-7", Map.of("resource", "kubernetes-ops"));

        String json = MAPPER.writeValueAsString(original);
        EventEnvelope<Map<String, Object>> restored = MAPPER.readValue(json, new TypeReference<>() { });

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void nullTraceIdAndNullPayloadSerializeAsJsonNull() throws Exception {
        EventEnvelope<Void> envelope = EventEnvelope.of("CANCELLED", "mars-cloud-order-service", "order-9", null);

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(envelope));

        assertThat(json.get("trace_id").isNull()).isTrue();
        assertThat(json.get("payload").isNull()).isTrue();
    }

    @Test
    void factoryGeneratesUuidAndCurrentTime() {
        Instant before = Instant.now();
        EventEnvelope<String> envelope = EventEnvelope.of("CREATED", "mars-cloud-order-service", "order-1", "x");

        assertThatCode(() -> UUID.fromString(envelope.eventId())).doesNotThrowAnyException();
        assertThat(envelope.occurredAt()).isBetween(before, Instant.now());
        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.withTraceId("abc").traceId()).isEqualTo("abc");
        assertThat(envelope.withTraceId("abc").withTraceId(null).traceId()).isNull();
        assertThat(envelope.withTraceId("abc")).isNotEqualTo(envelope);
    }

    @Test
    void rejectsBlankOrMalformedFields() {
        Instant now = Instant.now();
        assertThatNullPointerException().isThrownBy(() ->
                new EventEnvelope<>(null, "PAID", now, "mars-cloud-order-service", null, "order-1", "x"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventEnvelope<>(" ", "PAID", now, "mars-cloud-order-service", null, "order-1", "x"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventEnvelope<>("id", "paid", now, "mars-cloud-order-service", null, "order-1", "x"));
        assertThatNullPointerException().isThrownBy(() ->
                new EventEnvelope<>("id", "PAID", null, "mars-cloud-order-service", null, "order-1", "x"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventEnvelope<>("id", "PAID", now, "Order Service", null, "order-1", "x"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventEnvelope<>("id", "PAID", now, "mars-cloud-order-service", null, "", "x"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EventEnvelope<>("id", "PAID", now, "mars-cloud-order-service", " ", "order-1", "x"));
    }

    @Test
    void deserializationAppliesTheSameValidation() {
        String json = "{\"event_id\":\"id\",\"event_type\":\"paid\",\"occurred_at\":\"2026-09-21T10:15:30Z\","
                + "\"producer\":\"mars-cloud-order-service\",\"trace_id\":null,\"key\":\"order-1\",\"payload\":null}";

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                MAPPER.readValue(json, new TypeReference<EventEnvelope<Void>>() { })))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
