package com.mars.cloud.nacos.registration;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RegistrationTransportTest {
    static long deadline() { return System.nanoTime()+Duration.ofSeconds(4).toNanos(); }
    @ParameterizedTest @ValueSource(ints={401,403,503,307})
    void neverReplaysOrRedirects(int status) throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location","/other"); exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=server");
            exchange.sendResponseHeaders(status,-1); exchange.close();
        }); server.start();
        try(var transport=new NacosHttpTransport()) {
            var result=transport.call(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"test","POST",Map.of("key","value"),null,deadline(),()->true);
            assertThat(result.httpStatus()).isEqualTo(status); assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void rejectsMalformedSuccessAndExplicitlyDeletesEphemeralIdentity() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestURI().getQuery()).contains("ephemeral=true", "serviceName=example");
            byte[] body="{\"code\":\"not-a-number\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var transport=new NacosHttpTransport()) {
            var client=new NacosHttpRegistrationClient(transport);
            var snapshot=new RegistrationSnapshot("isolated","DEFAULT_GROUP","example","DEFAULT","127.0.0.1",8080,1,true,Map.of());
            assertThatThrownBy(()->client.remove(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"token",snapshot,deadline()))
                    .isInstanceOf(NacosHttpTransport.Failure.class);
            assertThat(requests.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void timeoutIsBoundedAndDoesNotReplayTheWrite() throws Exception {
        AtomicInteger requests=new AtomicInteger(); CountDownLatch release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{ requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try { release.await(); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close(); });server.start();
        try(var transport=new NacosHttpTransport()) {
            long start=System.nanoTime();
            assertThatThrownBy(()->transport.call(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"test","POST",Map.of(),null,deadline(),()->true))
                    .isInstanceOfSatisfying(NacosHttpTransport.Failure.class,failure->assertThat(failure.uncertain()).isTrue());
            assertThat(Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThan(2800);
            assertThat(requests.get()).isEqualTo(1);
        } finally { release.countDown();server.stop(0);executor.close(); }
    }
    @Test void oversizedResponseIsRejectedAfterExactlyOneWrite() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body=new byte[65537];
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var transport=new NacosHttpTransport()) {
            assertThatThrownBy(()->transport.call(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"test","POST",Map.of(),null,deadline(),()->true))
                    .isInstanceOfSatisfying(NacosHttpTransport.Failure.class,failure->assertThat(failure.uncertain()).isTrue());
            assertThat(requests.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void permissionLostBeforeDispatchDoesNotWrite() throws Exception {
        try(var transport=new NacosHttpTransport()) {
            assertThatThrownBy(()->transport.call(URI.create("http://127.0.0.1:1/"),"test","POST",Map.of(),null,deadline(),()->false))
                    .isInstanceOfSatisfying(NacosHttpTransport.Failure.class,failure->assertThat(failure.uncertain()).isFalse());
        }
    }
    @Test void stalledCheckCannotEnqueueMoreChecks() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var operation=new BoundedOperation("bounded-check-test")) {
            assertThatThrownBy(()->operation.call(()->{started.countDown();release.await();return true;},
                    System.nanoTime()+Duration.ofMillis(100).toNanos(),()->{})).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(started.getCount()).isZero(); assertThat(operation.busy()).isTrue();
            assertThatThrownBy(()->operation.call(()->true,deadline(),()->{})).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        } finally { release.countDown(); }
    }
}
