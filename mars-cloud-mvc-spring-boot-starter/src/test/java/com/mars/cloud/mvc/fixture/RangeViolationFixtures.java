package com.mars.cloud.mvc.fixture;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 故意制造错误码区间违规的测试夹具。
 *
 * <p><b>刻意放在 {@code com.mars.cloud.mvc.fixture} 子包</b>：测试宿主
 * {@code MvcTestApplication.App} 会对 {@code com.mars.cloud.mvc} 做组件扫描，
 * 若这些 {@code @Configuration} 落在被扫描的包里，正常测试的上下文也会被它们污染
 * （越界/重复的注册器会让所有测试一起启动失败）。
 */
public final class RangeViolationFixtures {

    private RangeViolationFixtures() {
    }

    /**
     * 越界：70001 不在声明的 61900–61999 区间内。
     */
    @Configuration(proxyBeanMethods = false)
    public static class OutOfRangeRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar outOfRangeRegistrar() {
            return () -> List.of((ErrorCode) () -> 70001);
        }
    }

    /**
     * 重复：同一个码注册两次。
     */
    @Configuration(proxyBeanMethods = false)
    public static class DuplicateRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar duplicateRegistrar() {
            return () -> List.of((ErrorCode) () -> 61902, (ErrorCode) () -> 61902);
        }
    }

    /**
     * 恰好落在区间边界（start / end）。
     */
    @Configuration(proxyBeanMethods = false)
    public static class BoundaryRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar boundaryRegistrar() {
            return () -> List.of((ErrorCode) () -> 61900, (ErrorCode) () -> 61999);
        }
    }
}
