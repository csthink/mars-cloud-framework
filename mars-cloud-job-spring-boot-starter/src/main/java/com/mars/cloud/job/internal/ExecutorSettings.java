package com.mars.cloud.job.internal;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 核验通过后的执行器设置，启动期解析一次，之后不再变化。
 *
 * @param appName         执行器名：隔离环境前缀加应用名，调度中心按它归组
 * @param bindAddress     执行器端口的监听地址；null 表示监听全部网卡
 * @param port            执行器端口；0 表示随机端口
 * @param registerHost    注册给调度中心的主机；null 表示使用监听地址
 * @param adminAddresses  调度中心地址，按顺序尝试
 * @param accessToken     调度中心与执行器共用的访问令牌
 * @param adminTimeout    调用调度中心的连接与读取超时
 * @param logPath         执行日志文件的目录
 * @param logRetention    执行日志文件的保留时长
 * @param shutdownTimeout 关闭时等待执行中任务的时长
 */
public record ExecutorSettings(String appName, InetAddress bindAddress, int port, String registerHost,
                               List<URI> adminAddresses, String accessToken, Duration adminTimeout,
                               Path logPath, Duration logRetention, Duration shutdownTimeout) {

    public ExecutorSettings {
        adminAddresses = List.copyOf(adminAddresses);
    }

    /**
     * 注册给调度中心的地址，形如 {@code http://192.0.2.8:10101/}。调度中心把它去掉末尾斜杠后拼接口路径。
     *
     * @param actualPort 实际监听的端口（配置为随机端口时由绑定结果给出）
     */
    public String registeredAddress(int actualPort) {
        String host = registerHost != null ? registerHost : bindAddress.getHostAddress();
        boolean ipv6 = registerHost == null ? bindAddress instanceof Inet6Address
                : !host.startsWith("[") && host.indexOf(':') >= 0;
        return "http://" + (ipv6 ? "[" + host + "]" : host) + ":" + actualPort + "/";
    }

    @Override
    public String toString() {
        // 访问令牌不进任何日志与异常消息
        return "ExecutorSettings[appName=" + appName + ", bindAddress=" + (bindAddress == null ? "*" : bindAddress.getHostAddress())
                + ", port=" + port + ", registerHost=" + registerHost + ", adminAddresses=" + adminAddresses
                + ", logPath=" + logPath + "]";
    }
}
