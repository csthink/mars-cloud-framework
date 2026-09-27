package com.mars.cloud.job.autoconfigure;

import com.mars.cloud.common.messaging.MessagingNames;
import com.mars.cloud.job.internal.ExecutorSettings;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 在启动期解析并核验执行器的约定。不满足即启动失败，异常消息写出规则与收到的值；访问令牌只报长度，不报内容。
 *
 * <p>执行器使用的设置只从这里取（{@link #settings()}），解析与核验只有一处。
 */
public final class JobConventionVerifier implements InitializingBean {

    /** 调度中心页面对执行器名的规则：小写字母开头，只含字母、数字与连字符，长度 4 到 64。 */
    static final Pattern APP_NAME = Pattern.compile("^[a-z][a-zA-Z0-9-]{3,63}$");
    static final int MIN_TOKEN_LENGTH = 16;
    private static final Pattern HOST_NAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$");

    private final Environment environment;
    private final JobProperties properties;
    private ExecutorSettings settings;

    public JobConventionVerifier(Environment environment, JobProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        settings = resolve(environment, properties);
    }

    /** 核验通过后的执行器设置。 */
    public ExecutorSettings settings() {
        if (settings == null) {
            settings = resolve(environment, properties);
        }
        return settings;
    }

    static ExecutorSettings resolve(Environment environment, JobProperties properties) {
        String appName = appName(environment, properties.getPrefix());
        InetAddress bindAddress = specificLiteral(environment.getProperty("server.address"));
        int port = port(environment, properties.getExecutor());
        String registerHost = registerHost(properties.getExecutor().getRegisterHost());
        require(bindAddress != null || registerHost != null,
                "执行器监听全部网卡（server.address 未配置、为通配地址或主机名，收到: " + environment.getProperty("server.address")
                        + "），调度中心无法连接通配地址；请配置 server.address 为具体 IP，或设置 MARS_JOB_REGISTER_HOST");
        List<URI> admins = adminAddresses(properties.getAdmin().getAddresses());
        String token = accessToken(properties.getAccessToken());
        Duration timeout = properties.getAdmin().getTimeout();
        require(timeout != null && !timeout.isNegative() && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                        && timeout.compareTo(Duration.ofSeconds(10)) <= 0,
                "mars.job.admin.timeout 必须在 1 到 10 秒之间，收到: " + timeout);
        Duration retention = properties.getExecutor().getLogRetention();
        require(retention != null && retention.compareTo(Duration.ofDays(1)) >= 0,
                "mars.job.executor.log-retention 至少 1 天，收到: " + retention);
        Duration shutdown = properties.getExecutor().getShutdownTimeout();
        require(shutdown != null && shutdown.isPositive(), "mars.job.executor.shutdown-timeout 必须大于 0，收到: " + shutdown);
        return new ExecutorSettings(appName, bindAddress, port, registerHost, admins, token, timeout,
                logPath(properties.getExecutor().getLogPath(), appName), retention, shutdown);
    }

    private static String appName(Environment environment, String prefix) {
        try {
            MessagingNames.requirePrefix(prefix);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("mars.job.prefix（环境变量 MARS_MQ_PREFIX）不合法：" + invalid.getMessage(), invalid);
        }
        String application = environment.getProperty("spring.application.name");
        require(application != null && !application.isBlank(), "执行器名由 spring.application.name 得出，它不能为空");
        String appName = prefix + application.trim();
        require(APP_NAME.matcher(appName).matches(),
                "执行器名（隔离环境前缀加 spring.application.name）必须小写字母开头、只含字母数字与连字符、长度 4 到 64，收到: "
                        + appName);
        return appName;
    }

    private static int port(Environment environment, JobProperties.Executor executor) {
        int offset = executor.getPortOffset();
        require(offset > 0, "mars.job.executor.port-offset 必须大于 0，收到: " + offset);
        Integer serverPort = MarsJobDefaultsEnvironmentPostProcessor.serverPort(environment);
        Integer port = executor.getPort();
        require(port != null, "无法推导执行器端口：业务端口 server.port 未配置或为随机端口（收到: "
                + environment.getProperty("server.port") + "），请显式配置 mars.job.executor.port");
        require(port >= 0 && port <= 65535, "mars.job.executor.port 必须在 0 到 65535 之间，收到: " + port);
        if (port != 0 && serverPort != null && serverPort > 0) {
            int expected = serverPort + offset;
            require(port == expected, "mars.job.executor.port 与约定不符：期望 " + expected + "（业务端口 " + serverPort
                    + " 加偏移量 " + offset + "），实际 " + port);
        }
        return port;
    }

    private static List<URI> adminAddresses(List<String> configured) {
        List<URI> addresses = new ArrayList<>();
        for (String entry : configured) {
            for (String part : entry.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    addresses.add(adminAddress(value));
                }
            }
        }
        require(!addresses.isEmpty(), "缺少调度中心地址：设置环境变量 MARS_JOB_ADMIN_ADDRESSES（http://主机:端口，多个以逗号分隔）");
        return addresses;
    }

    private static URI adminAddress(String value) {
        URI uri;
        try {
            uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("调度中心地址不是合法的 URI，收到: " + value, invalid);
        }
        require(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null,
                "调度中心地址必须是 http(s)://主机:端口，不带路径、查询与用户信息，收到: " + value);
        return uri;
    }

    private static String accessToken(String token) {
        require(token != null && !token.isBlank(),
                "缺少访问令牌：设置环境变量 MARS_JOB_ACCESS_TOKEN（与调度中心的 xxl.job.accessToken 相同）");
        String trimmed = token.trim();
        require(trimmed.length() >= MIN_TOKEN_LENGTH,
                "访问令牌至少 " + MIN_TOKEN_LENGTH + " 个字符，实际 " + trimmed.length() + " 个");
        return trimmed;
    }

    private static String registerHost(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String host = configured.trim();
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.indexOf(':') >= 0) {
            try {
                InetAddress.ofLiteral(bare);
            } catch (IllegalArgumentException notAnAddress) {
                throw new IllegalStateException("注册主机（MARS_JOB_REGISTER_HOST）只写主机名或 IP，不带协议与端口，收到: " + host,
                        notAnAddress);
            }
            return bare;
        }
        require(HOST_NAME.matcher(bare).matches(),
                "注册主机（MARS_JOB_REGISTER_HOST）只写主机名或 IP，不带协议与端口，收到: " + host);
        return bare;
    }

    private static Path logPath(String configured, String appName) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            require(path.isAbsolute(), "mars.job.executor.log-path 必须是绝对路径，收到: " + configured);
            return path;
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "mars-job", appName);
    }

    /**
     * 具体的 IP 字面量；未配置、空白、通配地址或主机名时为 null，此时执行器监听全部网卡（与管理端口的规则一致）。
     */
    static InetAddress specificLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(value.trim());
            return address.isAnyLocalAddress() ? null : address;
        } catch (IllegalArgumentException notALiteral) {
            return null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
