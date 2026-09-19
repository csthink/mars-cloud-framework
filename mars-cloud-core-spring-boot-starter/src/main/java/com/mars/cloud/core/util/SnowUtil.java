package com.mars.cloud.core.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

/**
 * @since 2025-10-30 15:40
 */
public final class SnowUtil {

    // 默认兜底
    private static volatile Snowflake DELEGATE = IdUtil.getSnowflake(1, 1);

    private SnowUtil() {
    }

    public static void setDelegate(Snowflake snowflake) {
        DELEGATE = snowflake;
    }

    public static long getId() {
        return DELEGATE.nextId();
    }

    public static String getIdStr() {
        return DELEGATE.nextIdStr();
    }
}
