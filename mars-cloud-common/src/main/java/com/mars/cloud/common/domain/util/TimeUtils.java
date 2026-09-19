package com.mars.cloud.common.domain.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @since 2025/6/23 16:35
 */
public class TimeUtils {

    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    public static String localDateTimeToString(LocalDateTime localDateTime) {
        return localDateTime.format(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT));
    }

    public static Duration ttlUntilNext2AM() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime today2AM = now.toLocalDate().atTime(2, 0);
        LocalDateTime expireAt = now.isBefore(today2AM)
                ? today2AM
                : now.toLocalDate().plusDays(1).atTime(2, 0);
        return Duration.between(now, expireAt);
    }


}
