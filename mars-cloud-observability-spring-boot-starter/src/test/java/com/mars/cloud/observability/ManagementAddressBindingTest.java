package com.mars.cloud.observability;

import com.mars.cloud.observability.app.reactive.ReactiveProbeApplication;
import com.mars.cloud.observability.app.servlet.ServletProbeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实启动两种 Web 栈：业务端口绑定回环地址时，独立的管理端口同样只在回环地址上可连。
 *
 * <p>从本机的非回环地址连接两个端口都应被拒绝。先用一个绑定全部网卡的对照端口确认这个地址本身可连，
 * 否则「连不上」说明不了绑定地址。本机没有可连的非回环地址时用例被跳过；正式构建把跳过判为失败，
 * 所以构建环境要有可连的非回环地址。
 *
 * <p>应用使用不含进程环境变量的环境：运行构建的 shell 若导出了 {@code SERVER_ADDRESS} 或
 * {@code MANAGEMENT_SERVER_ADDRESS}，它们会覆盖这里给出的配置，结果就取决于构建环境。
 */
class ManagementAddressBindingTest {

    @Test void servletManagementPortListensOnlyOnTheServerAddress() throws IOException {
        assertLoopbackOnly(new SpringApplicationBuilder(ServletProbeApplication.class)
                .properties(common("servlet-binding-probe")));
    }

    @Test void reactiveManagementPortListensOnlyOnTheServerAddress() throws IOException {
        assertLoopbackOnly(new SpringApplicationBuilder(ReactiveProbeApplication.class)
                .properties(common("reactive-binding-probe"))
                .properties("spring.main.web-application-type=reactive",
                        // 本模块的测试 classpath 上同时有两种 Web 栈，理由见 ReactiveManagementSecurityTest。
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.tomcat.autoconfigure.actuate.web.server."
                                + "TomcatReactiveManagementContextAutoConfiguration"));
    }

    private static String[] common(String name) {
        return new String[] {
                "spring.application.name=" + name,
                "server.port=0",
                "server.address=127.0.0.1",
                "management.server.port=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        };
    }

    private static void assertLoopbackOnly(SpringApplicationBuilder builder) throws IOException {
        Optional<InetAddress> external = nonLoopbackAddress();
        assumeTrue(external.isPresent(), "本机没有非回环地址");
        InetAddress address = external.get();
        assumeTrue(reachableThroughAllInterfaces(address), "从非回环地址连不上本机全部网卡上的对照端口");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        try (ConfigurableApplicationContext context = builder.environment(environment).run()) {
            int businessPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            int managementPort = context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            assertThat(managementPort).isNotEqualTo(businessPort);
            assertThat(accepts(InetAddress.getLoopbackAddress(), managementPort)).as("回环地址上的管理端口").isTrue();
            assertThat(accepts(address, managementPort)).as("非回环地址上的管理端口").isFalse();
            assertThat(accepts(address, businessPort)).as("非回环地址上的业务端口").isFalse();
        }
    }

    private static boolean reachableThroughAllInterfaces(InetAddress address) throws IOException {
        try (ServerSocket control = new ServerSocket()) {
            control.bind(new InetSocketAddress(0));
            return accepts(address, control.getLocalPort());
        }
    }

    private static boolean accepts(InetAddress address, int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(address, port), 2000);
            return true;
        }
        catch (IOException refused) {
            return false;
        }
    }

    private static Optional<InetAddress> nonLoopbackAddress() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .filter(ManagementAddressBindingTest::usable)
                .flatMap(NetworkInterface::inetAddresses)
                .filter(candidate -> candidate instanceof Inet4Address)
                .filter(candidate -> !candidate.isLoopbackAddress() && !candidate.isLinkLocalAddress())
                .findFirst();
    }

    private static boolean usable(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback();
        }
        catch (SocketException unreadable) {
            return false;
        }
    }
}
