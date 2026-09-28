package com.mars.cloud.nacos.registration;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RegistrationWebApplicationTest {
    static final AtomicBoolean dependencyUp=new AtomicBoolean();
    static CountDownLatch runnerEntered,releaseRunner;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    static class Application {
        @Bean HealthIndicator dependencyHealthIndicator() { return ()->dependencyUp.get()?Health.up().build():Health.down().build(); }
        @Bean ApplicationRunner delayedRunner() { return args->{runnerEntered.countDown();if(!releaseRunner.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Runner test timed out");}; }
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NONE,true", "SERVLET,false"})
    void inactiveRegistrationKeepsCommonsFailFastCompatible(WebApplicationType type,boolean register) {
        runnerEntered=new CountDownLatch(1);releaseRunner=new CountDownLatch(0);
        SpringApplication application=new SpringApplication(Application.class);application.setWebApplicationType(type);
        application.setDefaultProperties(Map.ofEntries(
                Map.entry("spring.application.name","inactive-registration-test"),Map.entry("server.port","0"),
                Map.entry("spring.cloud.nacos.config.enabled","false"),Map.entry("spring.cloud.nacos.config.import-check.enabled","false"),
                Map.entry("spring.cloud.nacos.discovery.server-addr","127.0.0.1:1"),
                Map.entry("spring.cloud.nacos.discovery.namespace","test-namespace"),
                Map.entry("spring.cloud.nacos.discovery.register-enabled",register),
                Map.entry("spring.cloud.service-registry.auto-registration.fail-fast","true"),Map.entry("logging.level.root","WARN")));
        try(var context=application.run()) {
            assertThat(context.getBeansOfType(NacosRegistrationLifecycle.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.cloud.client.serviceregistry.AutoServiceRegistration.class)).hasSize(1);
            assertThat(context.getBeansOfType(ServiceRegistry.class)).isEmpty();
        }
    }
    @ParameterizedTest @EnumSource(value=WebApplicationType.class,names={"SERVLET","REACTIVE"})
    void startsOnlyAfterRunnerAndActualGroupThenDeletesBeforeContextDestruction(WebApplicationType type) throws Exception {
        runnerEntered=new CountDownLatch(1);releaseRunner=new CountDownLatch(1);dependencyUp.set(false);
        AtomicInteger registered=new AtomicInteger(),deleted=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String response;
            if(exchange.getRequestURI().getPath().endsWith("login")) response="{\"accessToken\":\"token\",\"tokenTtl\":18000}";
            else if(exchange.getRequestMethod().equals("POST")) {if(body.contains("heartBeat=false"))registered.incrementAndGet();response="{\"code\":0,\"data\":\"ok\"}";}
            else if(exchange.getRequestMethod().equals("DELETE")) {deleted.incrementAndGet();response="{\"code\":0,\"data\":\"ok\"}";}
            else response="{\"code\":0,\"data\":[]}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        ConfigurableApplicationContext context=null;
        try(var executor=Executors.newSingleThreadExecutor()) {
            SpringApplication application=new SpringApplication(Application.class);application.setWebApplicationType(type);
            application.setDefaultProperties(Map.ofEntries(
                    Map.entry("spring.application.name","web-registration-test"),Map.entry("server.port","0"),
                    Map.entry("spring.cloud.nacos.config.enabled","false"),Map.entry("spring.cloud.nacos.config.import-check.enabled","false"),
                    Map.entry("spring.cloud.nacos.discovery.server-addr","127.0.0.1:"+server.getAddress().getPort()),
                    Map.entry("spring.cloud.nacos.discovery.namespace","test-namespace"),Map.entry("spring.cloud.nacos.discovery.username","test-user"),
                    Map.entry("spring.cloud.nacos.discovery.password","test-password"),Map.entry("spring.cloud.nacos.discovery.ip","127.0.0.1"),
                    Map.entry("management.endpoint.health.probes.enabled","true"),
                    Map.entry("management.endpoint.health.group.readiness.include","readinessState,dependency"),
                    Map.entry("spring.cloud.service-registry.auto-registration.fail-fast","true"),
                    Map.entry("logging.level.root","WARN")));
            var started=executor.submit(()->application.run());
            assertThat(runnerEntered.await(15,TimeUnit.SECONDS)).isTrue();assertThat(registered.get()).isZero();
            releaseRunner.countDown();context=started.get(10,TimeUnit.SECONDS);
            assertThat(context.getBeansOfType(ServiceRegistry.class)).isEmpty();
            assertThat(context.getBean(NacosRegistrationLifecycle.class)).isNotNull();
            await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(registered.get()).isZero());
            dependencyUp.set(true);
            await().atMost(Duration.ofSeconds(4)).until(()->registered.get()==1);
            context.close();context=null;
            assertThat(deleted.get()).isEqualTo(1);
        } finally {releaseRunner.countDown();if(context!=null)context.close();server.stop(0);}
    }
}
