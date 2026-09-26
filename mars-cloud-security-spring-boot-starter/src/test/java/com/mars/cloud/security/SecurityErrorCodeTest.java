package com.mars.cloud.security;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorCodeTest {
    @Test
    void codesStayUniqueInsideTheSecurityRange() {
        var codes = Arrays.stream(SecurityErrorCode.values()).map(SecurityErrorCode::getCode).collect(Collectors.toSet());
        assertThat(codes).hasSize(SecurityErrorCode.values().length);
        assertThat(codes).allSatisfy(code -> assertThat(code).isBetween(62000, 62999));
        assertThat(SecurityErrorCode.values()).allSatisfy(code -> assertThat(code.getMsgKey()).isEqualTo("error.code." + code.getCode()));
    }

    @Test
    void revokedSessionIsAnUnauthorizedResponseDistinctFromInvalidToken() {
        assertThat(SecurityErrorCode.SESSION_REVOKED.getCode()).isEqualTo(62007);
        assertThat(SecurityErrorCode.SESSION_REVOKED.status()).isEqualTo(401);
        assertThat(SecurityErrorCode.SESSION_REVOKED.getCode()).isNotEqualTo(SecurityErrorCode.TOKEN_INVALID.getCode());
        assertThat(SecurityErrorCode.SESSION_REVOKED.defaultMessage()).isNotBlank();
    }
}
