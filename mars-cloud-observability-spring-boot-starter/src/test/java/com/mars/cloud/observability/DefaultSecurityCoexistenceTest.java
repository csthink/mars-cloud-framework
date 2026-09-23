package com.mars.cloud.observability;

import com.mars.cloud.observability.app.defaults.reactive.DefaultSecurityReactiveProbeApplication;
import com.mars.cloud.observability.app.defaults.servlet.DefaultSecurityServletProbeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部署物自己没有安全链时，Spring Boot 的默认安全链仍然生效。
 *
 * <p>Boot 的默认安全链只在还没有任何安全链时装配。管理端点认证链的自动配置排在 Boot 的 Web 安全装配之后：
 * 排在前面时它会先登记，Boot 的默认链随之退出，业务请求就不再受任何保护。
 */
class DefaultSecurityCoexistenceTest {

    private static final String[] ARGUMENTS = {
            "--server.port=0",
            "--management.server.port=0",
            "--mars.observability.management.username=ops",
            "--mars.observability.management.password=ops-secret"
    };

    @Test void servletDefaultChainStillProtectsBusinessRequests() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DefaultSecurityServletProbeApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(withName("default-security-servlet-probe"))) {
            assertDefaultsCoexist(context);
        }
    }

    @Test void reactiveDefaultChainStillProtectsBusinessRequests() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DefaultSecurityReactiveProbeApplication.class)
                .web(WebApplicationType.REACTIVE)
                // 理由同 ReactiveManagementSecurityTest：测试 classpath 上有两种 Web 栈。
                .run(withName("default-security-reactive-probe",
                        "--spring.autoconfigure.exclude=org.springframework.boot.tomcat.autoconfigure.actuate.web.server."
                                + "TomcatReactiveManagementContextAutoConfiguration"))) {
            assertDefaultsCoexist(context);
        }
    }

    private static void assertDefaultsCoexist(ConfigurableApplicationContext context) {
        int businessPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        int managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
        assertThat(ManagementEndpointAccess.status(businessPort, "/business")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ManagementEndpointAccess.status(managementPort, "/actuator/prometheus"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ManagementEndpointAccess
                .withCredentials(managementPort, "/actuator/prometheus", "ops", "ops-secret")
                .getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
    }

    private static String[] withName(String name, String... extra) {
        String[] arguments = new String[ARGUMENTS.length + 1 + extra.length];
        arguments[0] = "--spring.application.name=" + name;
        System.arraycopy(ARGUMENTS, 0, arguments, 1, ARGUMENTS.length);
        System.arraycopy(extra, 0, arguments, 1 + ARGUMENTS.length, extra.length);
        return arguments;
    }
}
