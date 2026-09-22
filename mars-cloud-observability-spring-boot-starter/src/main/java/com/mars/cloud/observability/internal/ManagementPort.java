package com.mars.cloud.observability.internal;

import org.springframework.core.env.Environment;

/**
 * 管理端口的推导规则：业务端口加偏移量。
 *
 * <p>业务端口为 0（随机端口测试）或未配置时不推导，管理端点留在业务端口上。
 */
public final class ManagementPort {

    /** 管理端点与业务端点共用一个端口。 */
    public static final int SAME_PORT = 0;
    /** 管理端点被禁用。本项目不允许，实例监控会因此看不到该实例。 */
    public static final int DISABLED = -1;

    private ManagementPort() {
    }

    /**
     * 按业务端口与偏移量算出管理端口。
     *
     * @return 推导出的管理端口；业务端口不是正整数或偏移量为 0 时返回 {@code null} 表示不推导
     */
    public static Integer derive(Integer serverPort, int offset) {
        if (serverPort == null || serverPort <= 0 || offset == 0) {
            return null;
        }
        return serverPort + offset;
    }

    /** 读取 {@code server.port}，不存在或不是整数时返回 {@code null}。 */
    public static Integer serverPort(Environment environment) {
        return readPort(environment, "server.port");
    }

    /** 读取 {@code management.server.port}，不存在或不是整数时返回 {@code null}。 */
    public static Integer managementPort(Environment environment) {
        return readPort(environment, "management.server.port");
    }

    private static Integer readPort(Environment environment, String key) {
        String raw = environment.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }
}
