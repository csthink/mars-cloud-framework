package com.mars.cloud.nacos;

import com.mars.cloud.nacos.autoconfigure.NacosConventionAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NacosConventionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NacosConventionAutoConfiguration.class));

    @Test
    void autoConfigurationImportsRegistersConvention() throws Exception {
        ClassPathResource imports = new ClassPathResource(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

        assertThat(imports.getContentAsString(StandardCharsets.UTF_8))
                .contains(NacosConventionAutoConfiguration.class.getName());
    }

    @Test
    void requiredImportsAreSharedThenApplication() {
        assertThat(NacosConfigDataConvention.requiredImports("mars-cloud-upms-service"))
                .containsExactly(
                        "nacos:shared-common.yaml?group=COMMON&refreshEnabled=true",
                        "nacos:mars-cloud-upms-service.yaml?group=DEFAULT_GROUP&refreshEnabled=true");
    }

    @Test
    void invalidApplicationNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NacosConfigDataConvention.applicationImport("UPMS_Service"))
                .withMessageContaining("小写 kebab-case");
    }

    @Test
    void explicitOfflineModeSkipsConventionValidation() {
        contextRunner
                .withPropertyValues(
                        "spring.cloud.nacos.config.enabled=false",
                        "spring.cloud.nacos.discovery.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void validConventionStarts() {
        validRunner().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingNamespaceFailsFast() {
        validRunner()
                .withPropertyValues("spring.cloud.nacos.config.namespace=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "spring.cloud.nacos.config.namespace 必须显式配置且不能为空");
                });
    }

    @Test
    void differentNamespacesFailFast() {
        validRunner()
                .withPropertyValues("spring.cloud.nacos.discovery.namespace=test-namespace")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Nacos Config 与 Discovery 必须使用同一个 Namespace ID");
                });
    }

    @Test
    void optionalImportsAreRejected() {
        validRunner()
                .withPropertyValues(
                        "spring.config.import[0]=optional:nacos:shared-common.yaml?group=COMMON&refreshEnabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("必须包含且不得 optional");
                });
    }

    @Test
    void applicationConfigMustFollowSharedConfig() {
        validRunner()
                .withPropertyValues(
                        "spring.config.import[0]=nacos:mars-cloud-upms-service.yaml?group=DEFAULT_GROUP&refreshEnabled=true",
                        "spring.config.import[1]=nacos:shared-common.yaml?group=COMMON&refreshEnabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("必须先导入共享配置，再导入应用配置");
                });
    }

    @Test
    void discoveryGroupMustStayDefaultGroup() {
        validRunner()
                .withPropertyValues("spring.cloud.nacos.discovery.group=ANOTHER_GROUP")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Nacos Discovery group 必须是 DEFAULT_GROUP");
                });
    }

    private ApplicationContextRunner validRunner() {
        return contextRunner.withPropertyValues(
                "spring.application.name=mars-cloud-upms-service",
                "spring.cloud.nacos.config.namespace=local-namespace-id",
                "spring.cloud.nacos.discovery.namespace=local-namespace-id",
                "spring.cloud.nacos.discovery.group=DEFAULT_GROUP",
                "spring.config.import[0]=nacos:shared-common.yaml?group=COMMON&refreshEnabled=true",
                "spring.config.import[1]=nacos:mars-cloud-upms-service.yaml?group=DEFAULT_GROUP&refreshEnabled=true");
    }
}
