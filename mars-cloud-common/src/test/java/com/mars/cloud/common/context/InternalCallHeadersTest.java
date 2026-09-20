package com.mars.cloud.common.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCallHeadersTest {

    @Test
    @DisplayName("内部身份请求头名称固定")
    void headerNamesAreStable() {
        assertThat(InternalCallHeaders.SUBJECT).isEqualTo("X-Mars-Subject");
        assertThat(InternalCallHeaders.CLIENT_ID).isEqualTo("X-Mars-Client-Id");
        assertThat(InternalCallHeaders.TENANT_ID).isEqualTo("X-Mars-Tenant-Id");
    }
}
