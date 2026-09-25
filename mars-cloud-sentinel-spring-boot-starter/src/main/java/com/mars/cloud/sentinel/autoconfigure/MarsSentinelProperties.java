package com.mars.cloud.sentinel.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Sentinel 组件的配置。
 *
 * @since 2026-09-25
 */
@ConfigurationProperties(prefix = "mars.sentinel")
public class MarsSentinelProperties {

    private final Rules rules = new Rules();
    private final Gateway gateway = new Gateway();

    public Rules getRules() {
        return rules;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public static class Rules {

        /** 启动期从 Nacos 读取每个规则 Data ID 的超时。 */
        private Duration readTimeout = Duration.ofSeconds(3);

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Gateway {

        /**
         * 存放客户端地址的交换属性名，由网关自己的入站过滤器在 Sentinel 网关过滤器之前写入。
         * 网关部署物必须配置；为空白时启动失败，不回退到 TCP 对端地址。
         */
        private String clientIpAttribute;

        public String getClientIpAttribute() {
            return clientIpAttribute;
        }

        public void setClientIpAttribute(String clientIpAttribute) {
            this.clientIpAttribute = clientIpAttribute;
        }
    }
}
