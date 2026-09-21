package com.mars.cloud.mvc.fixture;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 测试用例使用的配置类，用于故意制造错误码区间违规。
 *
 * <p><b>刻意放在 {@code com.mars.cloud.mvc.fixture} 子包</b>：测试宿主
 * {@code MvcTestApplication.App} 不做组件扫描（改用显式 {@code @Import}），
 * 但仍保持这个包布局——夹具不该和被测代码混在一起。
 *
 * <p>码值都取 <b>business</b> 区段（66000 段），因为 mvc 层要用来测「归属层未启用」。
 */
public final class RangeViolationFixtures {

    private RangeViolationFixtures() {
    }

    /**
     * 越界：声明 66000–66050，却注册 66099。
     */
    @Configuration(proxyBeanMethods = false)
    public static class OutOfRangeRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar outOfRangeRegistrar() {
            return () -> List.of((ErrorCode) () -> 66099);
        }
    }

    /**
     * 重复：同一个码注册两次。
     */
    @Configuration(proxyBeanMethods = false)
    public static class DuplicateRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar duplicateRegistrar() {
            return () -> List.of((ErrorCode) () -> 66002, (ErrorCode) () -> 66002);
        }
    }

    /**
     * 恰好落在测试声明区间 [66000, 66050] 的两个边界上。
     */
    @Configuration(proxyBeanMethods = false)
    public static class BoundaryRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar boundaryRegistrar() {
            return () -> List.of((ErrorCode) () -> 66000, (ErrorCode) () -> 66050);
        }
    }

    /**
     * 归属 security 层（62000 段）——测试宿主不使用这一段，因此可以安全地用来验证
     * 「该层未启用」与「启用后即被接受」两种情况。
     */
    @Configuration(proxyBeanMethods = false)
    public static class SecurityLayerRegistrarConfig {
        @Bean
        public ErrorCodeRegistrar securityLayerRegistrar() {
            return () -> List.of((ErrorCode) () -> 62001);
        }
    }
}
