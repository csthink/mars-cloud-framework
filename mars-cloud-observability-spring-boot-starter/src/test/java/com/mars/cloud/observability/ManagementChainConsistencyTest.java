package com.mars.cloud.observability;

import com.mars.cloud.observability.app.servlet.ServletProbeApplication;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 暴露面必须与认证链一致：认证链装配不起来时，需要认证的端点不能暴露。
 *
 * <p>认证链除了 Spring Security，还需要 Spring Boot 的 Web 安全模块（{@code EndpointRequest} 所在的模块）。
 * 部署物带了前者却没有声明 {@code spring-boot-starter-security}，或者关掉了 Web 安全装配时，
 * 要么暴露面收窄为 health 与 info，要么在非开发 profile 拒绝启动，不能让指标、日志级别与堆转储
 * 在管理端口上无认证可达。
 */
@ExtendWith(OutputCaptureExtension.class)
class ManagementChainConsistencyTest {

    /** 隐藏 Boot 的 Web 安全模块里管理端点匹配器所在的包，Spring Security 本身仍在。 */
    private static final String WEB_SECURITY_MODULE_PACKAGE = "org.springframework.boot.security.autoconfigure.actuate";

    private static FilteredClassLoader withoutWebSecurityModule() {
        return new FilteredClassLoader(WEB_SECURITY_MODULE_PACKAGE);
    }

    @Test void narrowsExposureWhenTheWebSecurityModuleIsAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mars.observability.management.username", "ops");
        environment.setProperty("mars.observability.management.password", "secret");
        SpringApplication application = new SpringApplication();
        application.setResourceLoader(new DefaultResourceLoader(withoutWebSecurityModule()));

        new MarsObservabilityDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
    }

    /** 缺模块时暴露面已收窄，没有无认证的端点，所以只告警，与没有 Spring Security 时一样。 */
    @Test void startsWithAWarningWhenTheWebSecurityModuleIsAbsent(CapturedOutput output) {
        new ApplicationContextRunner()
                .withClassLoader(withoutWebSecurityModule())
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("管理端点无法建立认证链，暴露面已收窄");
    }

    /**
     * 模块都在、凭据齐备，但认证链没有装配（部署物关掉了 Web 安全装配）：暴露面此时已经放开，
     * 非开发 profile 只能拒绝启动。
     */
    @Test void refusesToStartOutsideDevelopmentWhenTheChainIsNotBuilt() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("管理端点的认证链没有装配");
                });
    }

    @Test void warnsInDevelopmentWhenTheChainIsNotBuilt(CapturedOutput output) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret",
                        "mars.env.dev-profiles=local", "spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("管理端点的认证链没有装配");
    }

    /** 非 Web 应用不开 HTTP 端口，认证链本来就不会装配，不在核验范围内。 */
    @Test void nonWebApplicationsAreNotChecked() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * 真端口：部署物排除了认证链，而它自己的业务安全链放行全部请求。这时若照常启动，管理端口上的指标、
     * 日志级别与线程转储会匿名可读；所以非开发 profile 在启动期就失败，端口根本不会打开。
     */
    @Test void refusesToStartRatherThanServeUnauthenticatedEndpoints() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ServletProbeApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--spring.application.name=chain-consistency-probe",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--mars.observability.management.username=ops",
                        "--mars.observability.management.password=ops-secret",
                        "--spring.autoconfigure.exclude="
                                + "com.mars.cloud.observability.autoconfigure.ServletManagementSecurityAutoConfiguration")
                .close())
                .rootCause()
                .hasMessageContaining("管理端点的认证链没有装配");
    }
}
