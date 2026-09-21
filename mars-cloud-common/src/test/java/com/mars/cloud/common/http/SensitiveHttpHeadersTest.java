package com.mars.cloud.common.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveHttpHeadersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Authorization", "authorization", "AUTHORIZATION", "aUtHoRiZaTiOn",
            "Proxy-Authorization", "proxy-authorization", "PROXY-AUTHORIZATION",
            "Cookie", "cookie", "COOKIE",
            "Set-Cookie", "set-cookie", "SET-COOKIE",
            "X-Api-Key", "x-api-key", "X-API-KEY", "X-aPi-KeY"
    })
    void identifiesCredentialHeadersRegardlessOfCase(String name) {
        assertThat(SensitiveHttpHeaders.isSensitive(name)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Accept", "Content-Type", "traceparent", "X-Request-Id", "X-Mars-Subject",
            "X-Mars-Client-Id", "X-Mars-Tenant-Id", "Authorization-Info", "X-Custom-Token"
    })
    void leavesOtherHeadersAndAbsentNamesToTheCaller(String name) {
        assertThat(SensitiveHttpHeaders.isSensitive(name)).isFalse();
    }
}
