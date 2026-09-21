package com.mars.cloud.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Strict JSON validation for the service-to-service permission protocol. */
public final class PdpProtocol {
    public static final String SERVICE = "mars-cloud-upms-service";
    public static final String PATH = "/upms/v1/decision";
    private PdpProtocol() { }
    public record Request(@JsonProperty("caller_id") String callerId, String action, String resource) { }
    public static PdpDecision decision(JsonNode envelope) {
        JsonNode result = envelope == null ? null : envelope.get("result");
        if (envelope == null || !envelope.isObject() || !envelope.path("success").isBoolean()
                || !envelope.path("success").booleanValue() || result == null || !result.isObject()
                || !text(result.get("decision")) || !text(result.get("reason_code")) || !text(result.get("decision_id")))
            throw new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR);
        String decision = result.get("decision").stringValue();
        if (!("allow".equals(decision) || "deny".equals(decision)))
            throw new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR);
        return new PdpDecision("allow".equals(decision), result.get("reason_code").stringValue(), result.get("decision_id").stringValue());
    }
    private static boolean text(JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isBlank();
    }
}
