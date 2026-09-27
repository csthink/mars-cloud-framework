package com.mars.cloud.job.autoconfigure;

import com.mars.cloud.job.internal.JobExecutor;
import com.mars.cloud.job.internal.JobHandlerRegistry;
import com.mars.cloud.job.internal.JobTracing;
import com.mars.cloud.job.internal.MicrometerJobTracing;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 周期任务组件的装配入口：核验约定、登记任务方法、启动执行器。
 *
 * <p>{@code mars.job.enabled=false} 时什么都不装配，{@link com.mars.cloud.job.JobHandler} 方法只是普通方法。
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "mars.job.enabled", matchIfMissing = true)
@EnableConfigurationProperties(JobProperties.class)
public class MarsJobAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JobConventionVerifier marsJobConventionVerifier(Environment environment, JobProperties properties) {
        return new JobConventionVerifier(environment, properties);
    }

    @Bean
    JobHandlerRegistry marsJobHandlerRegistry() {
        return new JobHandlerRegistry();
    }

    @Bean
    JobExecutor marsJobExecutor(JobConventionVerifier verifier, JobHandlerRegistry registry, JobTracing tracing) {
        return new JobExecutor(verifier.settings(), registry, tracing);
    }

    /**
     * 部署物打开延迟初始化时，这三个 Bean 仍在启动期创建：核验器与任务方法登记没有别的 Bean 依赖，
     * 延迟后会静默跳过；执行器作为生命周期 Bean 也要在启动期绑定端口，端口冲突才能让启动失败。
     */
    @Bean
    static LazyInitializationExcludeFilter marsJobBeansExcludedFromLazyInitialization() {
        return LazyInitializationExcludeFilter.forBeanTypes(JobConventionVerifier.class, JobHandlerRegistry.class,
                JobExecutor.class);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JobTracing marsJobTracing(ObjectProvider<Tracer> tracer) {
            return new MicrometerJobTracing(tracer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    static class NoTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JobTracing marsJobTracing() {
            return JobTracing.NONE;
        }
    }
}
