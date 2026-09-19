package com.mars.cloud.mvc.workthread;

import java.util.Map;

/**
 * @since 2025-05-23 16:10
 */
@FunctionalInterface
public interface Worker<P, R> {

    void execute(P p, Map<String, R> map) throws Exception;
}
