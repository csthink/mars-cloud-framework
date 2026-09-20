package com.mars.cloud.feign.autoconfigure;

import com.mars.cloud.feign.DownstreamFailureMapper;
import com.mars.cloud.feign.MarsFeignCapability;
import com.mars.cloud.feign.internal.CallerContextRequestInterceptor;
import com.mars.cloud.feign.internal.DownstreamFailureMapperRegistry;
import com.mars.cloud.feign.internal.ServiceInstanceRetryGuard;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientFactoryBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import feign.Capability;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * mars-cloud 的阻塞式服务间调用约定。
 */
@AutoConfiguration(afterName = "org.springframework.cloud.openfeign.FeignAutoConfiguration")
@ConditionalOnClass(Feign.class)
@EnableConfigurationProperties({OpenFeignClientLimitProperties.class, LoadBalancerLimitProperties.class})
public class MarsFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Retryer marsFeignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    @ConditionalOnMissingBean
    Request.Options marsFeignRequestOptions() {
        return new Request.Options(1, TimeUnit.SECONDS, 3, TimeUnit.SECONDS, true);
    }

    @Bean
    @ConditionalOnMissingBean
    CallerContextRequestInterceptor callerContextRequestInterceptor() {
        return new CallerContextRequestInterceptor();
    }

    @Bean
    static BeanFactoryPostProcessor feignClientUrlVerifier(
            Environment environment) {
        return beanFactory -> {
            for (String name : beanFactory.getBeanDefinitionNames()) {
                var definition = beanFactory.getBeanDefinition(name);
                Object url = null;
                if (FeignClientFactoryBean.class.getName()
                        .equals(definition.getBeanClassName())) {
                    url = definition.getPropertyValues().get("url");
                }
                if (definition.getAttribute("feignClientsRegistrarFactoryBean") instanceof
                        FeignClientFactoryBean factory) {
                    url = AnnotatedElementUtils.findMergedAnnotation(
                            factory.getType(), FeignClient.class).url();
                }
                if (url instanceof String value && !environment.resolveRequiredPlaceholders(value).isBlank()) {
                    throw new IllegalStateException("内部 Feign client 不得配置 URL；必须经服务名与 LoadBalancer 调用");
                }
            }
        };
    }

    @Bean
    ServiceInstanceRetryGuard serviceInstanceRetryGuard() {
        return new ServiceInstanceRetryGuard();
    }

    @Bean
    DownstreamFailureMapperRegistry downstreamFailureMapperRegistry(
            ObjectProvider<DownstreamFailureMapper> mapperProvider) {
        List<DownstreamFailureMapper> mappers = mapperProvider.orderedStream().toList();
        return new DownstreamFailureMapperRegistry(mappers);
    }

    @Bean
    Capability marsFeignCapability(DownstreamFailureMapperRegistry mappers,
                                    ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(() -> JsonMapper.builder().build());
        return new MarsFeignCapability(mappers, objectMapper);
    }

    @Bean
    FeignConventionVerifier feignConventionVerifier(
            OpenFeignClientLimitProperties feign,
            LoadBalancerLimitProperties loadBalancer,
            Retryer retryer,
            Request.Options options) {
        return new FeignConventionVerifier(feign, loadBalancer, retryer, options);
    }
}
