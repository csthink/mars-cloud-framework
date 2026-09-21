package com.mars.cloud.security.web;

import java.util.Locale;
import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.security.SecurityErrorCode;
import org.springframework.context.MessageSource;
import tools.jackson.databind.json.JsonMapper;

/** Shared envelope encoding, with no request or exception details. */
public final class SecurityResponses {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MessageSource messages;
    public SecurityResponses(MessageSource messages) { this.messages = messages; }
    public byte[] body(SecurityErrorCode code, Locale locale) {
        return JSON.writeValueAsBytes(UnifyResponse.fail(code.getCode(),
                messages.getMessage(code.getMsgKey(), null, code.defaultMessage(), locale)));
    }
    public static String challenge(SecurityErrorCode code) {
        return code == SecurityErrorCode.TOKEN_MISSING ? "Bearer" : "Bearer error=\"invalid_token\"";
    }
}
