package com.mars.cloud.feign;

import com.mars.cloud.feign.autoconfigure.MarsFeignAutoConfiguration;
import feign.Client;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.assertThat;

class FeignClientConfigurationContractTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class, MarsFeignAutoConfiguration.class))
            .withBean(Client.class, () -> (request, options) -> {
                throw new AssertionError("启动校验不应发起网络请求");
            });

    @Test
    void annotationUrlIsRejected() {
        runner.withUserConfiguration(UrlApplication.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("不得配置 URL");
        });
    }

    @Test
    void lazyAnnotationUrlIsRejected() {
        runner.withPropertyValues("spring.cloud.openfeign.lazy-attributes-resolution=true")
                .withUserConfiguration(UrlApplication.class).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("不得配置 URL");
                });
    }

    @Test
    void clientSpecificRetryerIsRejected() {
        runner.withUserConfiguration(RetryApplication.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("Retryer.NEVER_RETRY");
        });
    }

    @Test
    void clientSpecificOptionsCannotRelaxTimeout() {
        runner.withPropertyValues("spring.cloud.openfeign.client.default-to-properties=false")
                .withUserConfiguration(OptionsApplication.class).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("1..3000ms");
                });
    }

    @FeignClient(name = "inventory", url = "http://example.test")
    interface UrlClient { @GetMapping("/value") String get(); }

    @FeignClient(name = "inventory", configuration = CustomRetry.class)
    interface RetryClient { @GetMapping("/value") String get(); }

    @FeignClient(name = "inventory", configuration = CustomOptions.class)
    interface OptionsClient { @GetMapping("/value") String get(); }

    static class CustomRetry { @Bean Retryer retryer() { return new Retryer.Default(); } }
    static class CustomOptions { @Bean Request.Options options() { return new Request.Options(1000, 3001); } }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = UrlClient.class)
    static class UrlApplication { }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = RetryClient.class)
    static class RetryApplication { @Bean Object eagerlyCreate(RetryClient client) { return new Object(); } }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = OptionsClient.class)
    static class OptionsApplication { @Bean Object eagerlyCreate(OptionsClient client) { return new Object(); } }
}
