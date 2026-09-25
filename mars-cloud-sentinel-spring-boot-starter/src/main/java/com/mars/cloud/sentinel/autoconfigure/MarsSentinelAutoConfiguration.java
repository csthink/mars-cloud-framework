package com.mars.cloud.sentinel.autoconfigure;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.mars.cloud.feign.MarsFeignCapability;
import com.mars.cloud.sentinel.feign.SentinelFeignCapability;
import com.mars.cloud.sentinel.internal.BlockedRequestMetricExtension;
import com.mars.cloud.sentinel.internal.BlockedRequestRecorder;
import com.mars.cloud.sentinel.internal.MicrometerBlockedRequestRecorder;
import com.mars.cloud.sentinel.internal.MicrometerRuleUpdateRecorder;
import com.mars.cloud.sentinel.rule.NacosRuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleUpdateRecorder;
import com.mars.cloud.sentinel.rule.SentinelRuleCatalog;
import com.mars.cloud.sentinel.rule.SentinelRuleSources;
import com.mars.cloud.sentinel.servlet.ForwardingBlockExceptionHandler;
import com.mars.cloud.sentinel.servlet.MethodQualifiedSentinelWebInterceptor;
import feign.Capability;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 规则来源、拦截计数、Servlet 拦截异常与 Feign 客户端资源的装配。网关部分见 {@link MarsSentinelGatewayAutoConfiguration}。
 *
 * @since 2026-09-25
 */
@AutoConfiguration(afterName = "com.alibaba.cloud.nacos.NacosConfigAutoConfiguration")
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", matchIfMissing = true)
@EnableConfigurationProperties(MarsSentinelProperties.class)
public class MarsSentinelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RuleConfigSource marsSentinelRuleConfigSource(ObjectProvider<NacosConfigManager> nacosConfigManager) {
        NacosConfigManager manager = nacosConfigManager.getIfAvailable();
        if (manager == null) {
            throw new IllegalStateException("Sentinel 规则只从 Nacos 读取，但应用没有启用 Nacos 配置"
                    + "（spring.cloud.nacos.config.enabled）；确实不需要 Sentinel 时显式设置 spring.cloud.sentinel.enabled=false");
        }
        return new NacosRuleConfigSource(manager.getConfigService());
    }

    @Bean
    @ConditionalOnMissingBean
    SentinelRuleCatalog marsSentinelRuleCatalog() {
        return SentinelRuleCatalog.services();
    }

    @Bean
    SentinelRuleSources marsSentinelRuleSources(Environment environment, SentinelRuleCatalog catalog,
                                                RuleConfigSource configSource, MarsSentinelProperties properties,
                                                ObjectProvider<RuleUpdateRecorder> recorder) {
        return new SentinelRuleSources(environment.getProperty("spring.application.name"), catalog, configSource,
                properties.getRules().getReadTimeout(), recorder.getIfAvailable(() -> RuleUpdateRecorder.NONE));
    }

    /**
     * 有 Micrometer 时记录规则来源状态与拦截次数。{@code MeterRegistry} 在 bean 创建时按需获取，
     * 不依赖与指标自动装配的先后顺序。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        RuleUpdateRecorder marsSentinelRuleUpdateRecorder(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry available = registry.getIfAvailable();
            return available == null ? RuleUpdateRecorder.NONE : new MicrometerRuleUpdateRecorder(available);
        }

        @Bean
        BlockedRequestMetricsBinding marsSentinelBlockedRequestMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry available = registry.getIfAvailable();
            return new BlockedRequestMetricsBinding(available == null ? null : new MicrometerBlockedRequestRecorder(available));
        }
    }

    /** 把拦截计数接到 Sentinel 的指标扩展上，应用关闭时解除。 */
    static final class BlockedRequestMetricsBinding implements DisposableBean {

        private final BlockedRequestRecorder recorder;

        BlockedRequestMetricsBinding(BlockedRequestRecorder recorder) {
            this.recorder = recorder;
            if (recorder != null) {
                BlockedRequestMetricExtension.bind(recorder);
            }
        }

        @Override
        public void destroy() {
            if (recorder != null) {
                BlockedRequestMetricExtension.unbind(recorder);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(SentinelWebInterceptor.class)
    static class ServletConfiguration {

        @Bean
        BlockExceptionHandler marsSentinelBlockExceptionHandler() {
            return new ForwardingBlockExceptionHandler();
        }

        /**
         * 替换 Spring Cloud Alibaba 登记的拦截器：它的 {@code WebMvcConfigurer} 按类型注入唯一的拦截器，
         * {@code @Primary} 让它选中这一个。
         */
        @Bean
        @Primary
        @ConditionalOnProperty(name = "spring.cloud.sentinel.filter.enabled", matchIfMissing = true)
        SentinelWebInterceptor marsSentinelWebInterceptor(SentinelWebMvcConfig sentinelWebMvcConfig) {
            return new MethodQualifiedSentinelWebInterceptor(sentinelWebMvcConfig);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Capability.class, MarsFeignCapability.class})
    static class FeignConfiguration {

        @Bean
        SentinelFeignCapability marsSentinelFeignCapability() {
            return new SentinelFeignCapability();
        }
    }
}
