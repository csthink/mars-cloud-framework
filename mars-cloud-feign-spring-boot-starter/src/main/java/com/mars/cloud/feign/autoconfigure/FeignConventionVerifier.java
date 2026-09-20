package com.mars.cloud.feign.autoconfigure;

import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;
import java.util.Set;

/**
 * 在启动期拒绝放宽超时、叠加 Feign 重试或扩大 LoadBalancer 重试范围。
 */
public final class FeignConventionVerifier implements InitializingBean {

    static final int MAX_CONNECT_TIMEOUT_MILLIS = 1000;
    static final int MAX_READ_TIMEOUT_MILLIS = 3000;
    static final Set<Integer> ALLOWED_RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

    private final OpenFeignClientLimitProperties feign;
    private final LoadBalancerLimitProperties loadBalancer;
    private final Retryer retryer;
    private final Request.Options options;

    public FeignConventionVerifier(OpenFeignClientLimitProperties feign,
                                   LoadBalancerLimitProperties loadBalancer,
                                   Retryer retryer,
                                   Request.Options options) {
        this.feign = feign;
        this.loadBalancer = loadBalancer;
        this.retryer = retryer;
        this.options = options;
    }

    @Override
    public void afterPropertiesSet() {
        verifyRetryer(retryer);
        verifyOptions(options);

        for (Map.Entry<String, OpenFeignClientLimitProperties.Client> entry : feign.getConfig().entrySet()) {
            verifyFeignClient(entry.getKey(), entry.getValue());
        }

        verifyRetry("默认", loadBalancer.getRetry());
        for (Map.Entry<String, LoadBalancerLimitProperties.Client> entry : loadBalancer.getClients().entrySet()) {
            verifyRetry("client [" + entry.getKey() + "]", entry.getValue().getRetry());
        }
    }

    /** 校验 Feign 最终选中的重试器，包括客户端自己的配置。 */
    public static void verifyRetryer(Retryer retryer) {
        require(retryer == Retryer.NEVER_RETRY, "Feign Retryer 必须保持 Retryer.NEVER_RETRY");
    }

    /** 校验 Feign 最终选中的请求参数，包括客户端自己的配置。 */
    public static void verifyOptions(Request.Options options) {
        require(options.connectTimeoutMillis() > 0
                        && options.connectTimeoutMillis() <= MAX_CONNECT_TIMEOUT_MILLIS,
                "Feign 默认连接超时必须在 1..1000ms");
        require(options.readTimeoutMillis() > 0
                        && options.readTimeoutMillis() <= MAX_READ_TIMEOUT_MILLIS,
                "Feign 默认读取超时必须在 1..3000ms");

    }

    private static void verifyFeignClient(String name, OpenFeignClientLimitProperties.Client client) {
        if (client.getConnectTimeout() != null) {
            require(client.getConnectTimeout() > 0 && client.getConnectTimeout() <= MAX_CONNECT_TIMEOUT_MILLIS,
                    "Feign client [" + name + "] 的 connectTimeout 必须在 1..1000ms");
        }
        if (client.getReadTimeout() != null) {
            require(client.getReadTimeout() > 0 && client.getReadTimeout() <= MAX_READ_TIMEOUT_MILLIS,
                    "Feign client [" + name + "] 的 readTimeout 必须在 1..3000ms");
        }
        require(client.getRetryer() == null || client.getRetryer().isBlank(),
                "Feign client [" + name + "] 不得配置 Retryer；重试只走 Spring Cloud LoadBalancer");
        require(client.getUrl() == null || client.getUrl().isBlank(),
                "内部 Feign client [" + name + "] 不得配置 URL；必须经服务名与 LoadBalancer 调用");
    }

    private static void verifyRetry(String label, LoadBalancerLimitProperties.Retry retry) {
        if (retry == null || Boolean.FALSE.equals(retry.getEnabled())) {
            return;
        }
        if (retry.getMaxRetriesOnSameServiceInstance() != null) {
            require(retry.getMaxRetriesOnSameServiceInstance() == 0,
                    label + " LoadBalancer 不得重试同一个实例");
        }
        if (retry.getMaxRetriesOnNextServiceInstance() != null) {
            require(retry.getMaxRetriesOnNextServiceInstance() >= 0
                            && retry.getMaxRetriesOnNextServiceInstance() <= 1,
                    label + " LoadBalancer 下一实例重试次数不得超过 1");
        }
        require(!Boolean.TRUE.equals(retry.getRetryOnAllOperations()),
                label + " LoadBalancer 不得给写请求开启重试");
        require(!Boolean.FALSE.equals(retry.getAvoidPreviousInstance()),
                label + " LoadBalancer 必须避开上一失败实例");
        require(ALLOWED_RETRYABLE_STATUS_CODES.containsAll(retry.getRetryableStatusCodes()),
                label + " LoadBalancer retryable-status-codes 只能是 502、503、504 的子集");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
