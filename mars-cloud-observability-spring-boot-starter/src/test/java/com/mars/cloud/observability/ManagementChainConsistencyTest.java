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
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 暴露面必须与认证链一致：认证链没有装配时，生效的暴露清单只能包含 health 与 info。
 *
 * <p>认证链没有装配有三种原因：缺凭据；classpath 上缺 Spring Security 或 Spring Boot 的 Web 安全模块
 * （{@code EndpointRequest} 所在的模块）；部署物关掉了 Web 安全装配或排除了认证链的自动配置。
 * 显式配置的 {@code management.endpoints.web.exposure.include} 会覆盖组件写入的默认值，
 * 所以核验读生效的清单。越界时非开发 profile 拒绝启动、开发 profile 告警，
 * 不能让指标、日志级别与堆转储在管理端口上无认证可达。
 */
@ExtendWith(OutputCaptureExtension.class)
class ManagementChainConsistencyTest {

    /** 隐藏 Boot 的 Web 安全模块里管理端点匹配器所在的包，Spring Security 本身仍在。 */
    private static final String WEB_SECURITY_MODULE_PACKAGE = "org.springframework.boot.security.autoconfigure.actuate";
    /** 隐藏 Spring Security 本身。 */
    private static final String SPRING_SECURITY_PACKAGE = "org.springframework.security";

    private static final String UNPROTECTED_EXPOSURE = "暴露清单只能包含 health 与 info";

    private static FilteredClassLoader withoutWebSecurityModule() {
        return new FilteredClassLoader(WEB_SECURITY_MODULE_PACKAGE);
    }

    /**
     * 按真实启动的顺序先跑环境后处理，再装配核验器。上下文运行器不调用环境后处理器，
     * 不补这一步时暴露清单是 Boot 自己的默认值，测不到组件写入的清单。
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> componentDefaults() {
        return context -> {
            SpringApplication application = new SpringApplication();
            application.setResourceLoader(new DefaultResourceLoader(context.getClassLoader()));
            new MarsObservabilityDefaultsEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), application);
        };
    }

    private static WebApplicationContextRunner webApplication() {
        return new WebApplicationContextRunner()
                .withInitializer(componentDefaults())
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.observability.management.username=ops",
                        "mars.observability.management.password=secret");
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
        assertThat(output).contains("管理端点无法建立认证链").doesNotContain(UNPROTECTED_EXPOSURE);
    }

    /** 没有 Spring Security 时显式暴露指标端点：它会无认证可达，非开发 profile 拒绝启动。 */
    @Test void refusesExplicitExposureWithoutSpringSecurityOutsideDevelopment() {
        webApplication()
                .withClassLoader(new FilteredClassLoader(SPRING_SECURITY_PACKAGE))
                .withPropertyValues("management.endpoints.web.exposure.include=health,info,prometheus")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining(UNPROTECTED_EXPOSURE)
                            .hasMessageContaining("没有 Spring Security")
                            .hasMessageContaining("实际暴露了 [prometheus]");
                });
    }

    @Test void warnsAboutExplicitExposureWithoutSpringSecurityInDevelopment(CapturedOutput output) {
        webApplication()
                .withClassLoader(new FilteredClassLoader(SPRING_SECURITY_PACKAGE))
                .withPropertyValues("management.endpoints.web.exposure.include=health,info,prometheus",
                        "mars.env.dev-profiles=local", "spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains(UNPROTECTED_EXPOSURE).contains("实际暴露了 [prometheus]");
    }

    /** 通配符等于暴露全部端点。 */
    @Test void treatsTheWildcardAsExposingEveryEndpoint() {
        webApplication()
                .withClassLoader(new FilteredClassLoader(SPRING_SECURITY_PACKAGE))
                .withPropertyValues("management.endpoints.web.exposure.include=*")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("实际暴露了 [*]"));
    }

    /**
     * 模块都在、凭据齐备，但认证链没有装配（部署物关掉了 Web 安全装配）：组件已按有认证放开暴露面，
     * 非开发 profile 只能拒绝启动。
     */
    @Test void refusesToStartOutsideDevelopmentWhenTheChainIsNotBuilt() {
        webApplication().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining(UNPROTECTED_EXPOSURE)
                    .hasMessageContaining("认证链没有装配")
                    .hasMessageContaining("实际暴露了 [heapdump, loggers, metrics, prometheus, threaddump]");
        });
    }

    @Test void warnsInDevelopmentWhenTheChainIsNotBuilt(CapturedOutput output) {
        webApplication()
                .withPropertyValues("mars.env.dev-profiles=local", "spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains(UNPROTECTED_EXPOSURE).contains("认证链没有装配");
    }

    /** 部署物排除了认证链、同时把暴露清单限制在 health 与 info：没有无认证的端点，照常启动。 */
    @Test void acceptsAMissingChainWhenExposureIsLimitedToHealthAndInfo(CapturedOutput output) {
        webApplication()
                .withPropertyValues("management.endpoints.web.exposure.include=health,info")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain(UNPROTECTED_EXPOSURE);
    }

    /** 非 Web 应用不开 HTTP 端口，认证链本来就不会装配，不在核验范围内。 */
    @Test void nonWebApplicationsAreNotChecked() {
        new ApplicationContextRunner()
                .withInitializer(componentDefaults())
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
                .hasMessageContaining(UNPROTECTED_EXPOSURE)
                .hasMessageContaining("认证链没有装配");
    }

    /**
     * 真端口：用户名属性配成空白、由短环境变量给出时，暴露面、认证链条件与链里的账号读同一个值。
     * 开发 profile 下若判断不一致，暴露面按有认证放开而认证链不装配，指标端点会匿名可读。
     */
    @Test void blankPropertyAndShortVariableProtectTheEndpointsTheSameWay() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ServletProbeApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--spring.application.name=credential-probe",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--mars.observability.management.username=",
                        "--MARS_MANAGEMENT_USERNAME=ops",
                        "--mars.observability.management.password=ops-secret",
                        "--mars.env.dev-profiles=local",
                        "--spring.profiles.active=local")) {
            int managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            assertThat(ManagementEndpointAccess.status(managementPort, "/actuator/prometheus"))
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(ManagementEndpointAccess
                    .withCredentials(managementPort, "/actuator/prometheus", "ops", "ops-secret")
                    .getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        }
    }
}
