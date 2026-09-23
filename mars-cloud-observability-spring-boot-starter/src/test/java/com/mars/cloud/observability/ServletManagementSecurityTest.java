package com.mars.cloud.observability;

import com.mars.cloud.observability.app.servlet.ServletProbeApplication;
import com.mars.cloud.observability.security.ManagementCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet 栈的真端口验证：管理端点在管理端口上，health 匿名可读、其余要 Basic 认证，
 * 业务端口上没有管理端点，业务请求不受管理链影响。
 */
@SpringBootTest(classes = ServletProbeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=servlet-probe",
                "management.server.port=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        })
class ServletManagementSecurityTest {

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

    @Test void healthShowsComponentsToAuthorizedCallers() {
        ResponseEntity<String> response = ManagementEndpointAccess
                .withCredentials(managementPort, "/actuator/health", "ops", "ops-secret");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).contains("components");
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
        assertThat(allowed.getBody()).contains("application=\"servlet-probe\"");
    }

    /** 匿名请求得到带领域名的认证提示，且不因此建立会话：采集器与运维工具不带 Cookie，会话只会堆积。 */
    @Test void anonymousRequestsGetTheManagementRealmWithoutASession() {
        ResponseEntity<String> response = ManagementEndpointAccess.anonymous(managementPort, "/actuator/prometheus");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Basic realm=\"" + ManagementCredentials.REALM + "\"");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    /** 带凭据的请求同样不建立会话，每次请求都重新认证。 */
    @Test void authenticatedRequestsDoNotCreateASession() {
        ResponseEntity<String> response = ManagementEndpointAccess
                .withCredentials(managementPort, "/actuator/prometheus", "ops", "ops-secret");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test void actuatorIsNotReachableOnTheBusinessPort() {
        assertThat(ManagementEndpointAccess.status(businessPort, "/actuator/health"))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ManagementEndpointAccess.status(businessPort, "/actuator/prometheus"))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void theApplicationsOwnSecurityChainStillApplies() {
        ResponseEntity<String> response = ManagementEndpointAccess.anonymous(businessPort, "/business");
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getBody()).isEqualTo("business-ok");
    }
}
