package com.mars.cloud.common.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerContextTest {

    @Test
    @DisplayName("三个身份字段按原值保存")
    void fieldsArePreserved() {
        CallerContext context = new CallerContext("user-1", "portal", "default");

        assertThat(context.subject()).isEqualTo("user-1");
        assertThat(context.clientId()).isEqualTo("portal");
        assertThat(context.tenantId()).isEqualTo("default");
    }

    @Test
    @DisplayName("字段为空或空白时立即拒绝")
    void fieldsMustContainText() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CallerContext(null, "portal", "default"))
                .withMessage("subject 不能为空");
        assertThatThrownBy(() -> new CallerContext("user-1", " ", "default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("clientId 不能为空白");
        assertThatThrownBy(() -> new CallerContext("user-1", "portal", "\t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenantId 不能为空白");
    }
}
