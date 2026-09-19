package com.mars.cloud.mvc;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.MvcTestApplication.TestErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码与 i18n 机制的契约测试。
 */
@SpringBootTest(classes = MvcTestApplication.App.class)
class ErrorCodeContractTest {

    @Autowired
    private MessageSource messageSource;

    @Test
    @DisplayName("错误码契约给出规范 i18n key：error.code.<数字>")
    void errorCode_exposesCanonicalMessageKey() {
        assertThat(TestErrorCode.RESOURCE_NOT_FOUND.getCode()).isEqualTo(61901);
        assertThat(TestErrorCode.RESOURCE_NOT_FOUND.getMsgKey()).isEqualTo("error.code.61901");
    }

    @Test
    @DisplayName("按规范 key 能从 i18n 资源取到文案")
    void messageSource_resolvesCanonicalKey() {
        String message = messageSource.getMessage(
                TestErrorCode.RESOURCE_NOT_FOUND.getMsgKey(), null, LocaleContextHolder.getLocale());

        assertThat(message).isEqualTo("测试用资源不存在");
    }

    @Test
    @DisplayName("缺失的 key 走默认值，不抛异常")
    void missingKey_fallsBackToDefault() {
        String message = messageSource.getMessage("error.code.99999", null, "兜底文案", Locale.SIMPLIFIED_CHINESE);

        assertThat(message).isEqualTo("兜底文案");
    }

    @Test
    @DisplayName("ErrorCode 是纯接口：可用 lambda 直接实现（common 不依赖 Spring）")
    void errorCode_isFunctionalInterface() {
        ErrorCode adhoc = () -> 61999;

        assertThat(adhoc.getCode()).isEqualTo(61999);
        assertThat(adhoc.getMsgKey()).isEqualTo("error.code.61999");
    }
}
