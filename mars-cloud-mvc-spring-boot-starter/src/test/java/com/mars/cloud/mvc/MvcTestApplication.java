package com.mars.cloud.mvc;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.mvc.error.ErrorCodeRegistrar;
import lombok.Getter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

        RESOURCE_NOT_FOUND(61901),

        /**
         * 文案只存在于 application.yml 的 mars.codes 兜底里（i18n 资源刻意不写它）。
         */
        FALLBACK_ONLY(61990);

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

    /**
     * 验证「类级 @IgnoreResponseAnnotation 跳过包装」。
     */
    @RestController
    @IgnoreResponseAnnotation
    public static class IgnoredClassController {

        @GetMapping("/ignored-class")
        public Map<String, Object> raw() {
            return Map.of("raw", "class-level");
        }
    }

    /**
     * 测试宿主。
     *
     * <p><b>刻意用显式 {@code @Import} 而不是 {@code @ComponentScan}：</b>
     * 后者会连同包下的测试夹具（故意制造错误码越界/重复的 {@code @Configuration}）
     * 一起扫进来，让正常测试的上下文也被它们污染而启动失败。
     * 业务服务的真实形态是 {@code @SpringBootApplication}（含扫描），
     * 但测试宿主不需要它——starter 的装配只依赖 {@code @EnableAutoConfiguration}。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({TestErrorCodeRegistrar.class, IgnoredClassController.class})
    public static class App {
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
         * 方法级跳过包装 → 原样返回。
         */
        @IgnoreResponseAnnotation
        @GetMapping("/ignored")
        public Map<String, Object> ignored() {
            return Map.of("raw", "no-envelope");
        }

        /**
         * 普通字符串 → 必须包成 JSON 信封。
         */
        @GetMapping("/string-plain")
        public String stringPlain() {
            return "plain-text";
        }

        /**
         * 显式声明 text/plain → 不应被 JSON 化，用于验证修正没有误伤纯文本接口。
         */
        @IgnoreResponseAnnotation
        @GetMapping(value = "/string-text-plain", produces = "text/plain")
        public String stringTextPlain() {
            return "plain-text";
        }

        /**
         * 显式 text/plain 且内容是非拉丁字符 → 必须以 UTF-8 写出。
         * 这条钉住的是「容器里不能有 StringHttpMessageConverter 类型的 bean」：
         * 一旦有，Boot 就不再注册自己那个 UTF-8 的字符串转换器，默认链退回 ISO-8859-1。
         */
        @IgnoreResponseAnnotation
        @GetMapping(value = "/string-text-plain-cjk", produces = "text/plain")
        public String stringTextPlainCjk() {
            return "中文";
        }

        /**
         * 本身是 JSON 的字符串 → 应直接透传。
         */
        @GetMapping("/string-json")
        public String stringJson() {
            return "{\"name\":\"直接透传\"}";
        }

        /**
         * 文案不在 i18n 里、只在本地兜底配置里 → 走兜底链路。
         */
        @GetMapping("/fallback-error")
        public Payload fallbackError() {
            throw new com.mars.cloud.mvc.exception.BusinessException(TestErrorCode.FALLBACK_ONLY);
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
