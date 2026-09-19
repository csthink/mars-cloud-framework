package com.mars.cloud.core.id.snowflake;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.mars.cloud.core.api.IdGenerator;
import lombok.RequiredArgsConstructor;

/**
 * @since 2025-10-30 15:38
 * 默认 IdGenerator 实现（基于 Hutool Snowflake）
 */
@RequiredArgsConstructor
public class SnowflakeIdGenerator implements IdGenerator {

    private final Snowflake snowflake;

    @Override
    public long nextId() {
        return snowflake.nextId();
    }

    public static Snowflake build(long workerId, long dataCenterId) {
        return IdUtil.getSnowflake(workerId, dataCenterId);
    }
}
