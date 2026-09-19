package com.mars.cloud.mvc.error;

import com.mars.cloud.common.error.ErrorCode;

import java.util.Collection;

/**
 * @since 2025-10-29 14:48
 */
public interface ErrorCodeRegistrar {

    /**
     * 返回本服务定义的所有错误码
     */
    Collection<? extends ErrorCode> codes();
}
