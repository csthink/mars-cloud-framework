package com.mars.cloud.core.api;

/**
 * @since 2025-10-31 08:59
 * 统一 ID 生成接口，业务与上层只面向它编程
 */
@FunctionalInterface
public interface IdGenerator {

    long nextId();

    default String nextIdStr() {
        return String.valueOf(nextId());
    }
}
