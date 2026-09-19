package com.mars.cloud.mvc.util;

import cn.hutool.extra.spring.SpringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * @since 2025-10-29 10:02
 */
public final class I18nUtil {

    private static final Logger LOG = LoggerFactory.getLogger(I18nUtil.class);

    private static MessageSource messageSource = null;

    private static MessageSource getMessageSource() {
        if (messageSource == null) {
            messageSource = SpringUtil.getBean(MessageSource.class);
        }
        return messageSource;
    }

    public static String getMessage(int errorCode, String... args) {
        try {
            return getMessage(String.valueOf(errorCode), args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            LOG.error("No such message source: {}", errorCode);
            return "";
        }
    }

    public static String getMessage(String key, Object[] params) {
        return getMessage(key, params, LocaleContextHolder.getLocale());
    }

    public static String getMessage(String key, Object[] params, Locale locale) {
        try {
            if (locale != null && "ts".equals(locale.getLanguage())) {
                locale = new Locale("en", "US");
                return "(pseudo)_" + getMessageSource().getMessage(key, params, locale);
            }
            return getMessageSource().getMessage(key, params, locale);
        } catch (NoSuchMessageException e) {
            LOG.warn("No such message source: {}",  key);
            return "";
        }
    }

    public static String getMessage(String key, String defaultMsg, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        try {
            if (locale != null && "ts".equals(locale.getLanguage())) {
                locale = new Locale("en", "US");
                return "(pseudo)_" + getMessageSource().getMessage(key, args, defaultMsg, locale);
            }
            return getMessageSource().getMessage(key, args, defaultMsg, locale);
        } catch (Exception e) {
            LOG.warn("No such message source: {}", key);
            return defaultMsg;
        }
    }

    public static String getMessage(String key, String defaultMsg) {
        return getMessage(key, defaultMsg, (Object[]) null);
    }
}
