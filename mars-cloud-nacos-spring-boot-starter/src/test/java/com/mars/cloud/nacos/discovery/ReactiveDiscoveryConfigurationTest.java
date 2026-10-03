package com.mars.cloud.nacos.discovery;

import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.mars.cloud.nacos.autoconfigure.NacosDiscoveryLifecycleAutoConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.alibaba.nacos.api.exception.NacosException;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.DefaultServiceInstance;
import com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient;
import com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClientConfiguration;
import com.mars.cloud.nacos.autoconfigure.NacosReactiveDiscoveryLifecycleAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class ReactiveDiscoveryConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NacosReactiveDiscoveryLifecycleAutoConfiguration.class,
                    NacosReactiveDiscoveryClientConfiguration.class))
            .withUserConfiguration(Inputs.class);
    @Configuration(proxyBeanMethods=false)
    static class Inputs {
        @Bean NacosServiceDiscovery nacosServiceDiscovery() {return new CancellationSafeDiscoveryTest.Source();}
        @Bean DisposableBean nacosDiscoveryClientOwner() {return () -> {};}
    }
    @Test void replacesOnlyTheDefaultReactiveClient() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(NacosReactiveDiscoveryClient.class);
            assertThat(context.getBean(NacosReactiveDiscoveryClient.class)).isInstanceOf(CancellationSafeNacosReactiveDiscoveryClient.class);
        });
    }
    @Test void anApplicationClientIsNotReplaced() {
        runner.withBean("customDiscovery",NacosReactiveDiscoveryClient.class,
                () -> new NacosReactiveDiscoveryClient(new CancellationSafeDiscoveryTest.Source())).run(context -> {
            assertThat(context).hasSingleBean(NacosReactiveDiscoveryClient.class);
            assertThat(context).doesNotHaveBean(CancellationSafeNacosReactiveDiscoveryClient.class);
        });
    }
    @Test void explicitDiscoveryDisablingIsHonoured() {
        for(String key:List.of("spring.cloud.discovery.enabled","spring.cloud.discovery.reactive.enabled","spring.cloud.nacos.discovery.enabled"))
            runner.withPropertyValues(key+"=false").run(context -> assertThat(context).doesNotHaveBean(NacosReactiveDiscoveryClient.class));
    }
    @Test void servletApplicationsWithoutReactorDoNotLoadTheReactiveConfiguration() {
        new ApplicationContextRunner().withClassLoader(new FilteredClassLoader("reactor"))
                .withConfiguration(AutoConfigurations.of(NacosReactiveDiscoveryLifecycleAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed());
    }
    @Test void originalFailureToleranceConfigurationIsAppliedToEveryQuery() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(NacosReactiveDiscoveryClient.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        boolean additive=logger.isAdditive();logger.setAdditive(false);
        try {
            for(boolean enabled:List.of(false,true)) {
                var cached=List.<ServiceInstance>of(new DefaultServiceInstance("previous", "fallback", "127.0.0.1", 8080, false));
                com.alibaba.cloud.nacos.discovery.ServiceCache.setInstances("fallback",cached);
                com.alibaba.cloud.nacos.discovery.ServiceCache.setServiceIds(List.of("fallback"));
                new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(NacosReactiveDiscoveryLifecycleAutoConfiguration.class))
                        .withBean("nacosDiscoveryClientOwner",DisposableBean.class,() -> () -> {})
                        .withBean(NacosServiceDiscovery.class,() -> new NacosServiceDiscovery(null,null) {
                            @Override public List<ServiceInstance> getInstances(String id) throws NacosException {throw new NacosException(500,"controlled failure");}
                            @Override public List<String> getServices() throws NacosException {throw new NacosException(500,"controlled failure");}
                        }).withPropertyValues("spring.cloud.nacos.discovery.failure-tolerance-enabled="+enabled)
                        .run(context -> {
                            var client=context.getBean(CancellationSafeNacosReactiveDiscoveryClient.class);
                            for(int attempt=0;attempt<2;attempt++) {
                                assertThat(client.getInstances("fallback").collectList().block(CancellationSafeDiscoveryTest.TIMEOUT))
                                        .containsExactlyElementsOf(enabled ? cached : List.of());
                                assertThat(client.getServices().collectList().block(CancellationSafeDiscoveryTest.TIMEOUT))
                                        .containsExactlyElementsOf(enabled ? List.of("fallback") : List.of());
                            }
                        });
            }
        } finally {logger.detachAppender(appender);appender.stop();logger.setAdditive(additive);}
    }
    @Test void contextWaitsForDiscoveryBeforeDestroyingTheActualOwner() {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var closed=new AtomicBoolean();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                NacosDiscoveryLifecycleAutoConfiguration.class,NacosReactiveDiscoveryLifecycleAutoConfiguration.class))
                .withBean(NacosServiceManager.class,() -> new NacosServiceManager() {
                    @Override public void nacosServiceShutDown() {closed.set(true);}
                }).withBean(NacosServiceDiscovery.class,() -> new CancellationSafeDiscoveryTest.Source() {
                    @Override public List<ServiceInstance> getInstances(String id) throws NacosException {
                        entered.countDown();CancellationSafeDiscoveryTest.await(release);
                        assertThat(closed).isFalse();return super.getInstances(id);
                    }
                }).run(context -> {
                    var client=context.getBean(CancellationSafeNacosReactiveDiscoveryClient.class);
                    var subscription=client.getInstances("active").subscribe();CancellationSafeDiscoveryTest.await(entered);subscription.dispose();
                    try(var executor=Executors.newSingleThreadExecutor()) {
                        var closing=executor.submit(context::close);
                        assertThatThrownBy(() -> closing.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                        assertThat(closed).isFalse();release.countDown();closing.get(3,TimeUnit.SECONDS);
                        assertThat(closed).isTrue();
                    } finally {release.countDown();}
                });
    }
    @Test void configuredShutdownDurationUsesBootDurationSyntax() {
        for(String value:List.of("60s","PT60S"))
            runner.withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase="+value).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                        context.getBean(CancellationSafeNacosReactiveDiscoveryClient.class),"shutdownTimeout"))
                        .isEqualTo(java.time.Duration.ofSeconds(60));
            });
    }
}
