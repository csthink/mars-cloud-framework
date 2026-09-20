package com.mars.cloud.feign.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读取需要守卫的 Spring Cloud OpenFeign 标准配置。
 */
@ConfigurationProperties(prefix = "spring.cloud.openfeign.client")
public class OpenFeignClientLimitProperties {

    private Map<String, Client> config = new LinkedHashMap<>();

    public Map<String, Client> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Client> config) {
        this.config = config;
    }

    public static class Client {

        private Integer connectTimeout;
        private Integer readTimeout;
        private String retryer;
        private String url;

        public Integer getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Integer connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Integer getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Integer readTimeout) {
            this.readTimeout = readTimeout;
        }

        public String getRetryer() {
            return retryer;
        }

        public void setRetryer(String retryer) {
            this.retryer = retryer;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
