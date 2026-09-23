package com.mars.cloud.observability;

import com.mars.cloud.observability.security.ManagementCredentials;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 管理端点账号：口令用 bcrypt 编码，bcrypt 只接受不超过 72 字节的口令。 */
class ManagementCredentialsTest {

    @Test void acceptsAPasswordOfExactly72Bytes() {
        ManagementCredentials credentials = new ManagementCredentials("ops", "a".repeat(72));
        assertThat(credentials.encoder().matches("a".repeat(72), credentials.user().getPassword())).isTrue();
    }

    /** 超长时启动失败，消息写出属性名、上限与实际字节数；按 UTF-8 计，一个汉字占 3 字节。 */
    @Test void rejectsALongerPasswordWithAReadableMessage() {
        assertThatThrownBy(() -> new ManagementCredentials("ops", "口令".repeat(12) + "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mars.observability.management.password")
                .hasMessageContaining("72 字节")
                .hasMessageContaining("73");
    }
}
