package com.mars.cloud.mvc;

import com.mars.cloud.mvc.MvcTestApplication.App;
import com.mars.cloud.mvc.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码文案解析的三级兜底契约（T0.4）。
 *
 * <p>顺序：规范 i18n key → `mars.codes` 本地兜底配置 → 数字码本身。
 * 这条链路跨 i18n 与本地配置两个来源，升级时最容易静默退化。
 */
@SpringBootTest(classes = App.class)
class MessageResolutionTest {

    @Test
    @DisplayName("i18n 命中 → 用 i18n 文案")
    void i18nHit_wins() {
        BusinessException ex = new BusinessException(() -> 61901);

        assertThat(ex.getErrorMsg()).isEqualTo("测试用资源不存在");
    }

    @Test
    @DisplayName("i18n 未命中 → 异常自带文案回落为数字码本身（兜底配置不在这里生效）")
    void i18nMiss_exceptionOwnMessageFallsBackToCode() {
        BusinessException ex = new BusinessException(() -> 61990);

        assertThat(ex.getErrorMsg()).isEqualTo("61990");
    }

    @Test
    @DisplayName("两级都未命中 → 最终回落为数字码本身")
    void bothMiss_fallsBackToCode() {
        BusinessException ex = new BusinessException(() -> 61998);

        assertThat(ex.getErrorMsg()).isEqualTo("61998");
    }
}
