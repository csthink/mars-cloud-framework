package com.mars.cloud.feign.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class EnvelopeInspector {

    private final ObjectMapper objectMapper;

    EnvelopeInspector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    EnvelopeInspection inspect(byte[] body) {
        if (body == null || body.length == 0) {
            return EnvelopeInspection.malformed();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return EnvelopeInspection.malformed();
            }
            JsonNode success = root.get("success");
            if (success == null || !success.isBoolean()) {
                return EnvelopeInspection.malformed();
            }
            JsonNode code = root.get("code");
            String downstreamCode = code == null || code.isNull() ? null : code.stringValue();
            return new EnvelopeInspection(true, success.booleanValue(), downstreamCode);
        } catch (RuntimeException ex) {
            return EnvelopeInspection.malformed();
        }
    }
}
