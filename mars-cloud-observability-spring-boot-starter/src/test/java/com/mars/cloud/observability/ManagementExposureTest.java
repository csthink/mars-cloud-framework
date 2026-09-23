package com.mars.cloud.observability;

import com.jayway.jsonpath.JsonPath;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityAutoConfiguration;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.autoconfigure.ObservabilityProperties;
import com.mars.cloud.observability.internal.ManagementAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端点的暴露面：只有凭据齐备、且能建立认证链时才放开指标一类的端点。
 *
 * <p>本模块的测试 classpath 上有 Spring Security 与 Spring Boot 的 Web 安全模块，所以「缺凭据」与
 * 「凭据齐备」两种情况都能在这里覆盖；建不起认证链、或认证链没有装配的情况见 {@link ManagementChainConsistencyTest}。
 */
class ManagementExposureTest {

    /** 凭据齐备：完整清单。 */
    @Test void exposesFullSetWhenCredentialsArePresent() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops",
                "mars.observability.management.password", "secret")))
                .isEqualTo("health,info,prometheus,metrics,loggers,threaddump,heapdump");
    }

    /**
     * 配置元数据写出完整清单的默认值。注解处理器识别不了集合字段的初始值，默认值写在附加元数据里，
     * 这里核对它与代码里的常量一致。读本模块自己生成的文件，classpath 上别的 jar 也有同名文件。
     */
    @Test void configurationMetadataDocumentsTheDefaultExposure() throws Exception {
        Path classes = Path.of(ObservabilityProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String metadata = Files.readString(classes.resolve("META-INF/spring-configuration-metadata.json"));
        List<Object> defaults = JsonPath.read(metadata,
                "$.properties[?(@.name == 'mars.observability.management.exposure')].defaultValue");
        assertThat(defaults).containsExactly(List.of(ObservabilityProperties.Management.DEFAULT_EXPOSURE.split(",")));
    }

    /** 缺密码：收窄。只有用户名不构成凭据。 */
    @Test void narrowsWhenPasswordIsMissing() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops")))
                .isEqualTo("health,info");
    }

    /** 两项都缺：收窄。 */
    @Test void narrowsWhenCredentialsAreMissing() {
        assertThat(exposureAfterPostProcessing(Map.of())).isEqualTo("health,info");
    }

    /** 空白值不算凭据。 */
    @Test void treatsBlankCredentialsAsMissing() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "  ",
                "mars.observability.management.password", "secret")))
                .isEqualTo("health,info");
    }

    /** 能认证时的清单可以覆盖。 */
    @Test void honoursConfiguredExposureList() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops",
                "mars.observability.management.password", "secret",
                "mars.observability.management.exposure", "health,prometheus")))
                .isEqualTo("health,prometheus");
    }

    /**
     * 不能认证时的清单固定为 health 与 info，能认证时的清单配置项放不宽它：
     * 放宽后指标一类的端点会在没有认证链时暴露。
     */
    @Test void unauthenticatedExposureCannotBeWidenedByComponentProperties() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.exposure", "health,info,prometheus")))
                .isEqualTo("health,info");
    }

    /** 能认证时的清单按列表绑定：配置文件里写成列表与写成逗号分隔的字符串结果相同，不会静默退回默认清单。 */
    @Test void configuredExposureListMayBeWrittenAsAList() {
        assertThat(exposureAfterPostProcessing(Map.of(
                "mars.observability.management.username", "ops",
                "mars.observability.management.password", "secret",
                "mars.observability.management.exposure[0]", "health",
                "mars.observability.management.exposure[1]", "prometheus")))
                .isEqualTo("health,prometheus");
    }

    /**
     * 属性为空白时退回短环境变量，环境后处理与认证链条件必须得出同一个结论。
     * 两处结论不一致时，暴露面按有认证放开而认证链不装配，指标端点无认证可达。
     */
    @Test void blankPropertyFallsBackToTheShortVariableForEveryDecision() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mars.observability.management.username", "");
        environment.setProperty("MARS_MANAGEMENT_USERNAME", "ops");
        environment.setProperty("mars.observability.management.password", "secret");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus,metrics,loggers,threaddump,heapdump");
        assertThat(ManagementAccess.hasCredentials(environment)).isTrue();
    }

    /**
     * 缺凭据时非开发 profile 启动失败，消息说清楚缺什么。
     * 本模块的测试 classpath 上有 Spring Security，走的是「配置遗漏」那条路径；
     * 「没有 Spring Security」那条只告警不拒绝，由接入的部署物在真进程里体现。
     */
    @Test void failsOutsideDevelopmentProfilesWithoutCredentials() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("管理端点缺少认证凭据")
                            .hasMessageContaining("mars.observability.management.username");
                });
    }

    /**
     * 部署物打开延迟初始化时核验照样执行：核验器没有被别的 bean 依赖，延迟初始化下它不会被创建，
     * 核验就静默跳过。
     */
    @Test void verifiesEvenWithLazyInitialization() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()))
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed());
        new WebApplicationContextRunner()
                .withInitializer(context -> context.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()))
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.env.dev-profiles=local", "spring.profiles.active=local")
                .run(context -> assertThat(context.getBeanFactory().getBeanDefinition("marsManagementChainVerifier")
                        .isLazyInit()).isFalse());
    }

    /** 缺凭据时开发 profile 只告警，上下文照常启动。 */
    @Test void warnsInsteadOfFailingInDevelopmentProfiles() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarsObservabilityAutoConfiguration.class))
                .withPropertyValues("mars.env.dev-profiles=local,test", "spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 健康明细按授权显示：匿名只看到聚合状态。 */
    @Test void showsHealthDetailsOnlyToAuthorizedCallers() {
        MockEnvironment environment = new MockEnvironment();
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("when-authorized");
        assertThat(environment.getProperty("management.endpoint.health.show-components")).isEqualTo("when-authorized");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
    }

    /**
     * 部署时用的短环境变量名要能生效。它与属性名不在同一段，Boot 的宽松绑定接不上，
     * 组件显式映射了这一步；漏掉映射时凭据会静默失效、暴露面被收窄，而没有任何报错。
     */
    @Test void shortEnvironmentVariableNamesAreMapped() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("MARS_MANAGEMENT_USERNAME", "ops");
        environment.setProperty("MARS_MANAGEMENT_PASSWORD", "secret");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("mars.observability.management.username")).isEqualTo("ops");
        assertThat(environment.getProperty("mars.observability.management.password")).isEqualTo("secret");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus,metrics,loggers,threaddump,heapdump");
    }

    /** 显式属性优先于环境变量。 */
    @Test void explicitPropertiesWinOverEnvironmentVariables() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("MARS_MANAGEMENT_USERNAME", "from-variable");
        environment.setProperty("mars.observability.management.username", "from-property");
        environment.setProperty("MARS_MANAGEMENT_PASSWORD", "secret");
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("mars.observability.management.username")).isEqualTo("from-property");
    }

    private static String exposureAfterPostProcessing(Map<String, Object> properties) {
        MockEnvironment mock = new MockEnvironment();
        properties.forEach((key, value) -> mock.setProperty(key, String.valueOf(value)));
        ConfigurableEnvironment environment = mock;
        new MarsObservabilityDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("management.endpoints.web.exposure.include");
    }
}
