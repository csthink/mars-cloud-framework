package com.mars.cloud.mysql.mp;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.mars.cloud.core.api.IdGenerator;
import lombok.RequiredArgsConstructor;

/**
 * @since 2025-10-30 16:41
 */
@RequiredArgsConstructor
public class SnowflakeIdentifierGenerator implements IdentifierGenerator {

    private final IdGenerator idGen;

    @Override
    public Number nextId(Object entity) {
        return idGen.nextId();
    }

    @Override
    public String nextUUID(Object entity) {
        return idGen.nextIdStr();
    }
}
