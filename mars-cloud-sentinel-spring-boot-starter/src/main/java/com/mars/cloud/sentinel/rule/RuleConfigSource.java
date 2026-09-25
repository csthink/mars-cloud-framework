package com.mars.cloud.sentinel.rule;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 规则配置的读取与变更通知。默认实现是 {@link NacosRuleConfigSource}。
 *
 * @since 2026-09-25
 */
public interface RuleConfigSource {

    /**
     * 读取当前内容。
     *
     * @return 配置内容；Data ID 不存在时返回 {@code null}
     * @throws Exception 读取失败或超时
     */
    String read(String dataId, String group, Duration timeout) throws Exception;

    /**
     * 登记变更监听。配置被删除时监听器收到 {@code null} 或空白内容。
     *
     * @return 注销监听的句柄
     * @throws Exception 登记失败
     */
    Registration listen(String dataId, String group, Consumer<String> listener) throws Exception;

    /** 已登记的监听。 */
    interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
