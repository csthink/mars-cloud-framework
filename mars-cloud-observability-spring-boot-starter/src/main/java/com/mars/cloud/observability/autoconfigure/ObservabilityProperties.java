package com.mars.cloud.observability.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

        /** 能认证时的默认暴露清单。 */
        public static final String DEFAULT_EXPOSURE = "health,info,prometheus,metrics,loggers,threaddump,heapdump";

        /**
         * 管理端口相对业务端口的偏移量。默认 1000，即业务端口 8103 对应管理端口 9103。
         * 取 0 表示管理端点与业务端点共用一个端口，只允许开发 profile 使用。
         */
        private int portOffset = 1000;

        /**
         * 管理端点 Basic 认证的用户名。为空白时读环境变量 MARS_MANAGEMENT_USERNAME。
         * 用户名与密码任一缺失时只能暴露 health 与 info；能建认证链时，开发 profile 告警、其他 profile 启动失败。
         */
        private String username;

        /** 管理端点 Basic 认证的密码。为空白时读环境变量 MARS_MANAGEMENT_PASSWORD。 */
        private String password;

        /**
         * 能认证时的暴露清单，写入 management.endpoints.web.exposure.include。
         * 不能认证时固定为 health 与 info，不提供配置项。
         */
        private List<String> exposure = new ArrayList<>(List.of(DEFAULT_EXPOSURE.split(",")));

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

        public List<String> getExposure() {
            return exposure;
        }

        public void setExposure(List<String> exposure) {
            this.exposure = exposure;
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
