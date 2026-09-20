package com.mars.cloud.feign.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 只读取需要守卫的 Spring Cloud LoadBalancer 标准配置。
 */
@ConfigurationProperties(prefix = "spring.cloud.loadbalancer")
public class LoadBalancerLimitProperties {

    private Retry retry = new Retry();
    private Map<String, Client> clients = new LinkedHashMap<>();

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Map<String, Client> getClients() {
        return clients;
    }

    public void setClients(Map<String, Client> clients) {
        this.clients = clients;
    }

    public static class Client {

        private Retry retry = new Retry();

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }
    }

    public static class Retry {

        private Boolean enabled;
        private Integer maxRetriesOnSameServiceInstance;
        private Integer maxRetriesOnNextServiceInstance;
        private Boolean retryOnAllOperations;
        private Boolean avoidPreviousInstance;
        private Set<Integer> retryableStatusCodes = new LinkedHashSet<>();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxRetriesOnSameServiceInstance() {
            return maxRetriesOnSameServiceInstance;
        }

        public void setMaxRetriesOnSameServiceInstance(Integer value) {
            this.maxRetriesOnSameServiceInstance = value;
        }

        public Integer getMaxRetriesOnNextServiceInstance() {
            return maxRetriesOnNextServiceInstance;
        }

        public void setMaxRetriesOnNextServiceInstance(Integer value) {
            this.maxRetriesOnNextServiceInstance = value;
        }

        public Boolean getRetryOnAllOperations() {
            return retryOnAllOperations;
        }

        public void setRetryOnAllOperations(Boolean retryOnAllOperations) {
            this.retryOnAllOperations = retryOnAllOperations;
        }

        public Boolean getAvoidPreviousInstance() {
            return avoidPreviousInstance;
        }

        public void setAvoidPreviousInstance(Boolean avoidPreviousInstance) {
            this.avoidPreviousInstance = avoidPreviousInstance;
        }

        public Set<Integer> getRetryableStatusCodes() {
            return retryableStatusCodes;
        }

        public void setRetryableStatusCodes(Set<Integer> retryableStatusCodes) {
            this.retryableStatusCodes = retryableStatusCodes;
        }
    }
}
