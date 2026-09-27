package com.mars.cloud.job.autoconfigure;

import com.mars.cloud.job.internal.ExecutorSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接驱动解析与核验，不经过应用上下文：每条规则一个用例，失败消息写明规则与收到的值。
 */
class JobConventionVerifierTest {

    static final String TOKEN_VALUE = "test-access-token-0123456789";

    /** 一个满足全部约定的环境：业务端口 8103、回环地址、调度中心与令牌来自环境变量。 */
    static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.application.name", "sample-app")
                .withProperty("server.port", "8103")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("MARS_JOB_ADMIN_ADDRESSES", "http://127.0.0.1:28083")
                .withProperty("MARS_JOB_ACCESS_TOKEN", TOKEN_VALUE);
    }

    static ExecutorSettings resolve(MockEnvironment environment) {
        new MarsJobDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        JobProperties properties = Binder.get(environment).bindOrCreate("mars.job", JobProperties.class);
        return JobConventionVerifier.resolve(environment, properties);
    }

    private static void fails(MockEnvironment environment, String fragment) {
        assertThatThrownBy(() -> resolve(environment)).isInstanceOf(IllegalStateException.class).hasMessageContaining(fragment);
    }

    @Test
    void aValidEnvironmentResolvesToTheConventionalSettings() {
        ExecutorSettings settings = resolve(valid().withProperty("MARS_MQ_PREFIX", "s1-"));
        assertThat(settings.appName()).isEqualTo("s1-sample-app");
        assertThat(settings.port()).isEqualTo(10103);
        assertThat(settings.bindAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(settings.registerHost()).isNull();
        assertThat(settings.adminAddresses()).containsExactly(URI.create("http://127.0.0.1:28083"));
        assertThat(settings.accessToken()).isEqualTo(TOKEN_VALUE);
        assertThat(settings.adminTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.logPath()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), "mars-job", "s1-sample-app"));
        assertThat(settings.logRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.registeredAddress(settings.port())).isEqualTo("http://127.0.0.1:10103/");
    }

    @Test
    void severalAdminAddressesAreTriedInOrderAndATrailingSlashIsAccepted() {
        ExecutorSettings settings = resolve(valid().withProperty("MARS_JOB_ADMIN_ADDRESSES",
                "http://192.0.2.1:8080/, https://admin.example:8443"));
        assertThat(settings.adminAddresses()).containsExactly(URI.create("http://192.0.2.1:8080"),
                URI.create("https://admin.example:8443"));
    }

    @Test
    void theApplicationNameIsRequired() {
        fails(valid().withProperty("spring.application.name", " "), "spring.application.name 得出，它不能为空");
    }

    @Test
    void theIsolationPrefixFollowsTheMessagingRule() {
        fails(valid().withProperty("MARS_MQ_PREFIX", "S1"), "mars.job.prefix（环境变量 MARS_MQ_PREFIX）不合法");
    }

    @Test
    void theExecutorNameFollowsTheSchedulerRule() {
        fails(valid().withProperty("spring.application.name", "Sample"), "必须小写字母开头、只含字母数字与连字符、长度 4 到 64，收到: Sample");
        fails(valid().withProperty("spring.application.name", "app"), "收到: app");
        fails(valid().withProperty("spring.application.name", "sample_app"), "收到: sample_app");
        fails(valid().withProperty("spring.application.name", "a" + "b".repeat(64)), "长度 4 到 64");
    }

    @Test
    void theAccessTokenIsRequiredAndLongEnoughButNeverEchoed() {
        MockEnvironment missing = valid();
        missing.setProperty("MARS_JOB_ACCESS_TOKEN", " ");
        fails(missing, "缺少访问令牌：设置环境变量 MARS_JOB_ACCESS_TOKEN");
        assertThatThrownBy(() -> resolve(valid().withProperty("MARS_JOB_ACCESS_TOKEN", "short-secret")))
                .hasMessage("访问令牌至少 16 个字符，实际 12 个")
                .hasMessageNotContaining("short-secret");
    }

    @Test
    void adminAddressesAreRequiredAndMustBePlainHttpOrigins() {
        MockEnvironment missing = valid();
        missing.setProperty("MARS_JOB_ADMIN_ADDRESSES", "");
        fails(missing, "缺少调度中心地址：设置环境变量 MARS_JOB_ADMIN_ADDRESSES");
        fails(valid().withProperty("MARS_JOB_ADMIN_ADDRESSES", "http://127.0.0.1:28083/xxl-job-admin"),
                "不带路径、查询与用户信息，收到: http://127.0.0.1:28083/xxl-job-admin");
        fails(valid().withProperty("MARS_JOB_ADMIN_ADDRESSES", "tcp://127.0.0.1:28083"), "收到: tcp://127.0.0.1:28083");
        fails(valid().withProperty("MARS_JOB_ADMIN_ADDRESSES", "http://someone@127.0.0.1:28083"), "不带路径、查询与用户信息");
    }

    @Test
    void anExplicitPortMustMatchTheConvention() {
        fails(valid().withProperty("mars.job.executor.port", "10104"),
                "mars.job.executor.port 与约定不符：期望 10103（业务端口 8103 加偏移量 2000），实际 10104");
        assertThat(resolve(valid().withProperty("mars.job.executor.port", "10103")).port()).isEqualTo(10103);
        assertThat(resolve(valid().withProperty("mars.job.executor.port", "0")).port()).isZero();
    }

    @Test
    void aRandomBusinessPortNeedsAnExplicitExecutorPort() {
        fails(valid().withProperty("server.port", "0"), "无法推导执行器端口：业务端口 server.port 未配置或为随机端口（收到: 0）");
        assertThat(resolve(valid().withProperty("server.port", "0").withProperty("mars.job.executor.port", "0")).port()).isZero();
    }

    @Test
    void theOffsetMustBePositive() {
        fails(valid().withProperty("mars.job.executor.port-offset", "0"), "mars.job.executor.port-offset 必须大于 0，收到: 0");
    }

    @Test
    void aWildcardBindNeedsARegisterHost() {
        fails(valid().withProperty("server.address", "0.0.0.0"), "调度中心无法连接通配地址");
        fails(valid().withProperty("server.address", "app.example"), "收到: app.example");
        ExecutorSettings settings = resolve(valid().withProperty("server.address", "0.0.0.0")
                .withProperty("MARS_JOB_REGISTER_HOST", "192.0.2.9"));
        assertThat(settings.bindAddress()).isNull();
        assertThat(settings.registeredAddress(10103)).isEqualTo("http://192.0.2.9:10103/");
    }

    @Test
    void theRegisterHostIsAHostNameOrAnAddressOnly() {
        assertThat(resolve(valid().withProperty("MARS_JOB_REGISTER_HOST", "docker-host.example")).registeredAddress(10103))
                .isEqualTo("http://docker-host.example:10103/");
        assertThat(resolve(valid().withProperty("MARS_JOB_REGISTER_HOST", "[2001:db8::9]")).registeredAddress(10103))
                .isEqualTo("http://[2001:db8::9]:10103/");
        fails(valid().withProperty("MARS_JOB_REGISTER_HOST", "http://docker-host.example"), "不带协议与端口，收到: http://docker-host.example");
        fails(valid().withProperty("MARS_JOB_REGISTER_HOST", "docker-host.example:10103"), "收到: docker-host.example:10103");
    }

    @Test
    void timingSettingsStayInRange() {
        fails(valid().withProperty("mars.job.admin.timeout", "30s"), "mars.job.admin.timeout 必须在 1 到 10 秒之间，收到: PT30S");
        fails(valid().withProperty("mars.job.executor.log-retention", "12h"), "mars.job.executor.log-retention 至少 1 天，收到: PT12H");
        fails(valid().withProperty("mars.job.executor.shutdown-timeout", "0s"), "mars.job.executor.shutdown-timeout 必须大于 0");
    }

    @Test
    void anExplicitLogPathMustBeAbsolute() {
        fails(valid().withProperty("mars.job.executor.log-path", "logs/jobs"), "mars.job.executor.log-path 必须是绝对路径，收到: logs/jobs");
        assertThat(resolve(valid().withProperty("mars.job.executor.log-path", "/var/log/mars-job")).logPath())
                .isEqualTo(Path.of("/var/log/mars-job"));
    }
}
