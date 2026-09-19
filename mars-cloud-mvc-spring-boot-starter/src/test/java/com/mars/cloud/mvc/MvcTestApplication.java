package com.mars.cloud.mvc;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import lombok.Getter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 契约测试的宿主：
 *
 * <p>用完整的 {@code @SpringBootConfiguration} + {@code @EnableAutoConfiguration} 启动，
 * 这样 mvc starter 的 {@code AutoConfiguration.imports} 会被真实加载——
 * 与业务服务引入 starter 后的装配路径一致。控制器直接写在测试里，不依赖外部服务。
 */
public class MvcTestApplication {

    /**
     * 测试用错误码，落在 application.yml 声明的 61900–61999 区间内。
     */
    @Getter
    public enum TestErrorCode implements ErrorCode {

        RESOURCE_NOT_FOUND(61901);

        private final int code;

        TestErrorCode(int code) {
            this.code = code;
        }
    }

    /**
     * 注册给框架，启动时会被区间校验器校验（越界或重复即启动失败）。
     */
    public static class TestErrorCodeRegistrar implements ErrorCodeRegistrar {

        @Override
        public Collection<? extends ErrorCode> codes() {
            return List.of(TestErrorCode.values());
        }
    }

    /**
     * 用于验证响应的载体。
     */
    public record Payload(String name, int value) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackageClasses = MvcTestApplication.class)
    public static class App {

        @Bean
        public TestErrorCodeRegistrar testErrorCodeRegistrar() {
            return new TestErrorCodeRegistrar();
        }
    }

    @RestController
    public static class TestController {

        /**
         * 正常返回对象 → 应被包成信封。
         */
        @GetMapping("/ok")
        public Payload ok() {
            return new Payload("hello", 1);
        }

        /**
         * 空返回 → 应是 success 且无 result。
         */
        @GetMapping("/empty")
        public Payload empty() {
            return null;
        }

        /**
         * 业务规则拒绝：HTTP 200 + success=false + 业务错误码。
         */
        @GetMapping("/business-error")
        public Payload businessError() {
            throw new com.mars.cloud.mvc.exception.BusinessException(TestErrorCode.RESOURCE_NOT_FOUND);
        }

        /**
         * 参数校验失败：HTTP 400。
         */
        @GetMapping("/bad-request")
        public Payload badRequest(@RequestParam String required) {
            return new Payload(required, 0);
        }

        /**
         * 未预期异常：HTTP 500 + 兜底错误码。
         */
        @GetMapping("/boom")
        public Payload boom() {
            throw new IllegalStateException("boom");
        }

        /**
         * 路径参数类型不匹配：HTTP 400。
         */
        @GetMapping("/number/{id}")
        public Map<String, Object> number(@PathVariable Integer id) {
            return Map.of("id", id);
        }
    }
}
