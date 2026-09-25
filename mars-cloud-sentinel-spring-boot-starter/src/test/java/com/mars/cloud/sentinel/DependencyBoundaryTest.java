package com.mars.cloud.sentinel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 依赖足迹：排除的命令端口、集群限流实现与 Nacos 数据源不得回来。
 */
class DependencyBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "com.alibaba.csp.sentinel.transport.command.SimpleHttpCommandCenter",
            "com.alibaba.csp.sentinel.transport.heartbeat.SimpleHttpHeartbeatSender",
            "com.alibaba.csp.sentinel.cluster.client.DefaultClusterTokenClient",
            "com.alibaba.csp.sentinel.cluster.server.SentinelDefaultTokenServer",
            "com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource"
    })
    void excludedDependenciesAreAbsent(String className) {
        assertThat(isPresent(className)).as(className).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.alibaba.csp.sentinel.datasource.AbstractDataSource",
            "com.alibaba.csp.sentinel.logging.slf4j.RecordLogLogger",
            "com.alibaba.cloud.nacos.NacosConfigManager"
    })
    void requiredDependenciesArePresent(String className) {
        assertThat(isPresent(className)).as(className).isTrue();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, DependencyBoundaryTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
