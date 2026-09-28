package com.mars.cloud.nacos.registration;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class RegistrationLifecycleTest {
    interface TestContext extends WebServerApplicationContext, ConfigurableApplicationContext { }
    static class Harness implements AutoCloseable {
        final AtomicBoolean up=new AtomicBoolean(true),accepting=new AtomicBoolean(true),exists=new AtomicBoolean();
        final AtomicInteger checks=new AtomicInteger(),registers=new AtomicInteger(),renews=new AtomicInteger(),deletes=new AtomicInteger();
        final List<String> writes=new CopyOnWriteArrayList<>();
        final HttpServer server,backup;
        final java.util.concurrent.ExecutorService httpWorkers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        final java.util.concurrent.CountDownLatch healthArrived=new java.util.concurrent.CountDownLatch(1),releaseHealth=new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean delayHealth=new AtomicBoolean();
        final AtomicBoolean dropRegisterResponse=new AtomicBoolean(),delayRegisterResponse=new AtomicBoolean();
        final java.util.concurrent.CountDownLatch registerArrived=new java.util.concurrent.CountDownLatch(1),releaseResponse=new java.util.concurrent.CountDownLatch(1);
        final TestContext context=mock(TestContext.class);
        final MockEnvironment env=new MockEnvironment();
        final NacosServiceManager discovery=mock(NacosServiceManager.class);
        final NacosRegistrationLifecycle lifecycle;
        Harness() throws Exception { this(false); }
        Harness(boolean multiple) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(httpWorkers);
            var handler=server.createContext("/",exchange->{
                String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                String result;
                if(exchange.getRequestURI().getPath().endsWith("login")) result="{\"accessToken\":\"test-token\",\"tokenTtl\":18000}";
                else if(exchange.getRequestMethod().equals("POST")) {
                    if(body.contains("heartBeat=true")) { renews.incrementAndGet();writes.add("renew"); }
                    else {
                        registers.incrementAndGet();exists.set(true);writes.add(body);registerArrived.countDown();
                        if(dropRegisterResponse.get()) {exchange.close();return;}
                        if(delayRegisterResponse.get()) try {releaseResponse.await();} catch(InterruptedException ignored) {Thread.currentThread().interrupt();}
                    }
                    result="{\"code\":0,\"data\":\"ok\"}";
                } else if(exchange.getRequestMethod().equals("DELETE")) {
                    deletes.incrementAndGet();exists.set(false);writes.add("delete");result="{\"code\":0,\"data\":\"ok\"}";
                } else result="{\"code\":0,\"data\":[]}";
                byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            backup=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);backup.setExecutor(httpWorkers);
            backup.createContext("/",handler.getHandler());backup.start();
            env.withProperty("spring.application.name","example").withProperty("spring.cloud.nacos.discovery.namespace","isolated")
                    .withProperty("spring.cloud.nacos.discovery.server-addr","127.0.0.1:"+server.getAddress().getPort()+(multiple?",127.0.0.1:"+backup.getAddress().getPort():""))
                    .withProperty("spring.cloud.nacos.discovery.username","test-user").withProperty("spring.cloud.nacos.discovery.password","test-password");
            when(context.getEnvironment()).thenReturn(env);
            when(context.getBean(org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties.class))
                    .thenReturn(new org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties());
            var endpoint=mock(HealthEndpoint.class);when(endpoint.healthForPath("readiness")).thenAnswer(call->{checks.incrementAndGet();if(delayHealth.get()){healthArrived.countDown();releaseHealth.await();}var health=mock(org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor.class);when(health.getStatus()).thenReturn(up.get()?org.springframework.boot.health.contributor.Status.UP:org.springframework.boot.health.contributor.Status.DOWN);return health;});
            var group=mock(HealthEndpointGroup.class);when(group.isMember(anyString())).thenReturn(true);
            var groups=mock(HealthEndpointGroups.class);when(groups.get(anyString())).thenReturn(group);
            var registry=mock(HealthContributorRegistry.class);when(registry.getContributor(anyString())).thenReturn((HealthIndicator)()->Health.up().build());
            var availability=mock(ApplicationAvailability.class);when(availability.getReadinessState()).thenAnswer(call->accepting.get()?ReadinessState.ACCEPTING_TRAFFIC:ReadinessState.REFUSING_TRAFFIC);
            var evaluator=new NacosReadinessEvaluator(endpoint,groups,registry,null,availability);
            lifecycle=new NacosRegistrationLifecycle(context,new RegistrationConfiguration(context,List.of(),"127.0.0.1"),evaluator,discovery);
        }
        void port() {
            WebServer web=mock(WebServer.class);when(web.getPort()).thenReturn(8080);
            WebServerInitializedEvent event=mock(WebServerInitializedEvent.class);when(event.getApplicationContext()).thenReturn(context);when(event.getWebServer()).thenReturn(web);
            lifecycle.onApplicationEvent(event);
        }
        void ready() { lifecycle.onApplicationEvent(new ApplicationReadyEvent(new SpringApplication(),new String[0],context,Duration.ZERO)); }
        void refusing() {
            accepting.set(false);lifecycle.onApplicationEvent(new AvailabilityChangeEvent<>(context,ReadinessState.REFUSING_TRAFFIC));
        }
        @Override public void close() throws Exception { releaseResponse.countDown();releaseHealth.countDown();lifecycle.destroy();server.stop(0);backup.stop(0);httpWorkers.close(); }
    }
    @Test void waitsForRunnerAndWholeGroupThenWithdrawsOnReadinessAndKeepsDiscoveryUntilDestroy() throws Exception {
        try(var h=new Harness()) {
            h.port(); h.up.set(false);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).untilAsserted(()->assertThat(h.registers.get()).isZero());
            h.ready();await().atMost(Duration.ofSeconds(2)).until(()->h.checks.get()>0);assertThat(h.registers.get()).isZero();
            h.up.set(true);await().atMost(Duration.ofSeconds(3)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.REGISTERED);
            assertThat(h.registers.get()).isEqualTo(1);
            h.refusing();await().atMost(Duration.ofSeconds(2)).until(()->h.deletes.get()==1);
            assertThat(h.renews.get()).isZero();verify(h.discovery,never()).nacosServiceShutDown();
            h.lifecycle.closeRegistration();verify(h.discovery,never()).nacosServiceShutDown();
            assertThat(h.lifecycle.state()).isEqualTo(NacosRegistrationLifecycle.State.CLOSED);
        }
    }
    @Test void blockedReadinessNeverRegistersOrQueuesAnotherHealthCheck() throws Exception {
        try(var h=new Harness()) {
            h.delayHealth.set(true);h.port();h.ready();
            assertThat(h.healthArrived.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(()->{
                assertThat(h.registers.get()).isZero();assertThat(h.checks.get()).isEqualTo(1);
            });
            h.delayHealth.set(false);h.releaseHealth.countDown();
            await().atMost(Duration.ofSeconds(3)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.REGISTERED);
        }
    }
    @Test void registeredInstanceRecoversThroughAnotherAddressInTheOriginalCluster() throws Exception {
        try(var h=new Harness(true)) {
            h.port();h.ready();await().atMost(Duration.ofSeconds(3)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.REGISTERED);
            h.server.stop(0);
            await().atMost(Duration.ofSeconds(12)).until(()->h.registers.get()==2 && h.lifecycle.state()==NacosRegistrationLifecycle.State.REGISTERED);
            assertThat(h.deletes.get()).isEqualTo(1);
            assertThat(h.writes.get(1)).isEqualTo("delete");
        }
    }
    @Test void refreshDeletesTheOldSnapshotBeforeCreatingNewIdentity() throws Exception {
        try(var h=new Harness()) {
            h.port();h.ready();await().atMost(Duration.ofSeconds(3)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.REGISTERED);
            h.env.setProperty("spring.cloud.nacos.discovery.service","replacement");
            h.lifecycle.onApplicationEvent(new EnvironmentChangeEvent(h.context,Set.of("spring.cloud.nacos.discovery.service")));
            await().atMost(Duration.ofSeconds(4)).until(()->h.registers.get()==2);
            assertThat(h.writes.get(0)).contains("serviceName=example");assertThat(h.writes.get(1)).isEqualTo("delete");
            assertThat(h.writes.get(2)).contains("serviceName=replacement");
        }
    }
    @Test void unknownRegisterResultNeverAutomaticallyCreatesANewGeneration() throws Exception {
        try(var h=new Harness()) {
            h.dropRegisterResponse.set(true);h.port();h.ready();
            await().atMost(Duration.ofSeconds(3)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.UNCERTAIN);
            await().atMost(Duration.ofSeconds(4)).until(()->h.deletes.get()>0);
            h.dropRegisterResponse.set(false);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(()->assertThat(h.registers.get()).isEqualTo(1));
            assertThat(h.lifecycle.state()).isEqualTo(NacosRegistrationLifecycle.State.UNCERTAIN);
        }
    }
    @Test void closeWaitsForInFlightRegisterAndDeletesItsSnapshotWithoutPublishingSuccess() throws Exception {
        try(var h=new Harness();var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            h.delayRegisterResponse.set(true);h.port();h.ready();
            assertThat(h.registerArrived.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var closed=executor.submit(h.lifecycle::closeRegistration);
            await().atMost(Duration.ofSeconds(1)).until(()->h.lifecycle.state()==NacosRegistrationLifecycle.State.CLOSING);
            h.releaseResponse.countDown();closed.get(4,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(h.registers.get()).isEqualTo(1);assertThat(h.deletes.get()).isEqualTo(1);
            verify(h.context,never()).publishEvent(isA(org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent.class));
            assertThat(h.lifecycle.state()).isEqualTo(NacosRegistrationLifecycle.State.CLOSED);
        }
    }

}
