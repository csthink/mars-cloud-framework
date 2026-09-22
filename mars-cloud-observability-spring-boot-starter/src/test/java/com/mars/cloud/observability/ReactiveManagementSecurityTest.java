package com.mars.cloud.observability;

import com.mars.cloud.observability.app.reactive.ReactiveProbeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应式栈的真端口验证，语义与 Servlet 栈那份相同。
 * 接入 Spring Security 的响应式应用走的就是这条链。
 */
@SpringBootTest(classes = ReactiveProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=reactive-probe",
                "spring.main.web-application-type=reactive",
                "management.server.port=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret",
                // 本模块的测试 classpath 上同时有两种 Web 栈，两个管理子上下文工厂会撞同一个 bean 名。
                // 真实部署物只会有一种栈；这里排除 Servlet 容器那个，让响应式栈用自己的。
                // 类名写错会让排除静默失效，届时这条用例仍会因同一个冲突失败，所以失败是响亮的。
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.tomcat.autoconfigure.actuate.web.server."
                        + "TomcatReactiveManagementContextAutoConfiguration"
        })
class ReactiveManagementSecurityTest {

    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;

    @Test void managementPortIsSeparateFromTheBusinessPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(businessPort);
    }

    @Test void healthIsReadableAnonymouslyWithoutComponentDetails() {
        ResponseEntity<String> response = ManagementEndpointAccess.anonymous(managementPort, "/actuator/health");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test void metricsEndpointRequiresCorrectCredentials() {
        assertThat(ManagementEndpointAccess.anonymous(managementPort, "/actuator/prometheus")
                .getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(ManagementEndpointAccess
                .withCredentials(managementPort, "/actuator/prometheus", "ops", "wrong")
                .getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        ResponseEntity<String> allowed = ManagementEndpointAccess
                .withCredentials(managementPort, "/actuator/prometheus", "ops", "ops-secret");
        assertThat(allowed.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(allowed.getBody()).contains("application=\"reactive-probe\"");
    }

    @Test void actuatorIsNotReachableOnTheBusinessPort() {
        assertThat(ManagementEndpointAccess.status(businessPort, "/actuator/prometheus"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void theApplicationsOwnSecurityChainStillApplies() {
        ResponseEntity<String> response = ManagementEndpointAccess.anonymous(businessPort, "/business");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).isEqualTo("business-ok");
    }
}
