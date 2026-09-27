package com.mars.cloud.job.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期任务组件的配置，前缀 {@code mars.job}。
 *
 * <p>调度中心地址、访问令牌、注册主机与隔离环境前缀只从环境变量来（{@code MARS_JOB_ADMIN_ADDRESSES}、
 * {@code MARS_JOB_ACCESS_TOKEN}、{@code MARS_JOB_REGISTER_HOST}、{@code MARS_MQ_PREFIX}），
 * 它们与运行环境相关，不写进配置中心的共享配置。
 */
@ConfigurationProperties(prefix = "mars.job")
public class JobProperties {

    /** 是否启动执行器。单元测试与不连接调度中心的本机运行显式关闭。 */
    private boolean enabled = true;

    /** 隔离环境前缀，形如 {@code s1-}，加在执行器名前面；对应环境变量 MARS_MQ_PREFIX。 */
    private String prefix = "";

    /** 调度中心与执行器共用的访问令牌，至少 16 个字符；对应环境变量 MARS_JOB_ACCESS_TOKEN。 */
    private String accessToken;

    private final Admin admin = new Admin();

    private final Executor executor = new Executor();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix.trim();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Executor getExecutor() {
        return executor;
    }

    public static class Admin {

        /** 调度中心地址，{@code http(s)://主机:端口}，不带路径；多个地址按顺序尝试。对应环境变量 MARS_JOB_ADMIN_ADDRESSES。 */
        private List<String> addresses = new ArrayList<>();

        /** 调用调度中心的连接与读取超时，1 到 10 秒。 */
        private Duration timeout = Duration.ofSeconds(3);

        public List<String> getAddresses() {
            return addresses;
        }

        public void setAddresses(List<String> addresses) {
            this.addresses = addresses == null ? new ArrayList<>() : addresses;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Executor {

        /** 执行器端口；不配置时取业务端口加偏移量，显式配置必须等于这个值，0 表示随机端口（测试用）。 */
        private Integer port;

        /** 执行器端口相对业务端口的偏移量。 */
        private int portOffset = 2000;

        /**
         * 注册给调度中心的主机名或 IP。不配置时取监听地址（{@code server.address}）；调度中心在容器里、
         * 执行器只监听回环地址时写容器能访问宿主机的主机名。对应环境变量 MARS_JOB_REGISTER_HOST。
         */
        private String registerHost;

        /** 执行日志文件的目录；不配置时为临时目录下的 mars-job/执行器名。 */
        private String logPath;

        /** 执行日志文件保留时长，至少 1 天。 */
        private Duration logRetention = Duration.ofDays(7);

        /**
         * 关闭时等待执行中任务的时长；超过后中断任务。执行器在 Web 服务器优雅关闭之后停止，
         * 部署平台的终止宽限期要覆盖这两段等待，另加几秒用于线程收尾与回调发送。
         */
        private Duration shutdownTimeout = Duration.ofSeconds(30);

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public int getPortOffset() {
            return portOffset;
        }

        public void setPortOffset(int portOffset) {
            this.portOffset = portOffset;
        }

        public String getRegisterHost() {
            return registerHost;
        }

        public void setRegisterHost(String registerHost) {
            this.registerHost = registerHost;
        }

        public String getLogPath() {
            return logPath;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath;
        }

        public Duration getLogRetention() {
            return logRetention;
        }

        public void setLogRetention(Duration logRetention) {
            this.logRetention = logRetention;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }
    }
}
