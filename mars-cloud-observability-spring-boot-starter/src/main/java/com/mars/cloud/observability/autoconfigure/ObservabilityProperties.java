package com.mars.cloud.observability.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性组件自己的配置项。链路追踪、指标与结构化日志沿用 Spring Boot 的属性名，
 * 只有下面这些推导与核验所需的取值放在 {@code mars.observability} 前缀下。
 */
@ConfigurationProperties(prefix = "mars.observability")
public class ObservabilityProperties {

    private final Management management = new Management();
    private final Logging logging = new Logging();

    public Management getManagement() {
        return management;
    }

    public Logging getLogging() {
        return logging;
    }

    public static class Management {

        /**
         * 管理端口相对业务端口的偏移量。默认 1000，即业务端口 8103 对应管理端口 9103。
         * 取 0 表示管理端点与业务端点共用一个端口，只允许开发 profile 使用。
         */
        private int portOffset = 1000;

        /** 管理端点 Basic 认证的用户名。与密码一起缺失时按开发 profile 收窄暴露面、其他 profile 启动失败。 */
        private String username;

        /** 管理端点 Basic 认证的密码。 */
        private String password;

        /** 管理端点的暴露清单，写入 management.endpoints.web.exposure.include。 */
        private String exposure = "health,info,prometheus,metrics,loggers,threaddump,heapdump";

        /** 缺少认证凭据或缺少 Spring Security 时退回的暴露清单。 */
        private String restrictedExposure = "health,info";

        public int getPortOffset() {
            return portOffset;
        }

        public void setPortOffset(int portOffset) {
            this.portOffset = portOffset;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getExposure() {
            return exposure;
        }

        public void setExposure(String exposure) {
            this.exposure = exposure;
        }

        public String getRestrictedExposure() {
            return restrictedExposure;
        }

        public void setRestrictedExposure(String restrictedExposure) {
            this.restrictedExposure = restrictedExposure;
        }

        /** 两项都有值才能建立 Basic 认证链。 */
        public boolean hasCredentials() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }

    public static class Logging {

        /** 控制台日志格式：ecs 为结构化 JSON，plain 为 Spring Boot 的文本格式（只在本机调试时用）。 */
        private ConsoleFormat consoleFormat = ConsoleFormat.ECS;

        public ConsoleFormat getConsoleFormat() {
            return consoleFormat;
        }

        public void setConsoleFormat(ConsoleFormat consoleFormat) {
            this.consoleFormat = consoleFormat;
        }
    }

    public enum ConsoleFormat {
        /** Elastic Common Schema 的 JSON 格式，每行一条，带 traceId 与 spanId。 */
        ECS,
        /** Spring Boot 默认的文本格式，不写 logging.structured.format.console。 */
        PLAIN
    }
}
