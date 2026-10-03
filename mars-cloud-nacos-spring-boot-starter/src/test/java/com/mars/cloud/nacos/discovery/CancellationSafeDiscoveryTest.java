package com.mars.cloud.nacos.discovery;

import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.nacos.api.exception.NacosException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.scheduler.Schedulers;
import static org.assertj.core.api.Assertions.*;

class CancellationSafeDiscoveryTest {
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    static class Source extends NacosServiceDiscovery {
        final AtomicInteger calls = new AtomicInteger();
        Source() { super(null, null); }
        @Override public List<ServiceInstance> getInstances(String id) throws NacosException {
            return List.of(new DefaultServiceInstance("instance-" + calls.incrementAndGet(), id, "127.0.0.1", 8080, false));
        }
        @Override public List<String> getServices() { return List.of("service-" + calls.incrementAndGet()); }
    }
    static Schedulers.Snapshot useDiscoveryScheduler(reactor.core.scheduler.Scheduler scheduler) {
        return Schedulers.setFactoryWithSnapshot(new Schedulers.Factory() {
            @Override public reactor.core.scheduler.Scheduler newBoundedElastic(int cap, int queue,
                    java.util.concurrent.ThreadFactory factory, int ttl) { return scheduler; }
            @Override public reactor.core.scheduler.Scheduler newThreadPerTaskBoundedElastic(int cap, int queue,
                    java.util.concurrent.ThreadFactory factory) { return scheduler; }
        });
    }
    @Test void aPublisherThatIsNeverSubscribedDoesNoWork() throws Exception {
        var source = new Source(); var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        client.getInstances("example"); client.getServices(); client.destroy();
        assertThat(source.calls).hasValue(0);
    }
    @Test void eachSubscriptionQueriesAgainInsteadOfKeepingAnInstanceList() throws Exception {
        var source = new Source(); var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var publisher = client.getInstances("example");
        assertThat(publisher.blockFirst(TIMEOUT).getInstanceId()).isEqualTo("instance-1");
        assertThat(publisher.blockFirst(TIMEOUT).getInstanceId()).isEqualTo("instance-2");
        var services = client.getServices();
        assertThat(services.collectList().block(TIMEOUT)).containsExactly("service-3");
        assertThat(services.collectList().block(TIMEOUT)).containsExactly("service-4");
        client.destroy();
    }
    @Test void cancellingQueuedWorkDoesNotStartTheDelegate() throws Exception {
        var scheduler = Schedulers.newSingle("queued-discovery-test");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var source = new Source(); var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var snapshot = useDiscoveryScheduler(scheduler);
        try {
            scheduler.schedule(() -> { entered.countDown(); await(release); }); await(entered);
            var instances = List.<ServiceInstance>of(new DefaultServiceInstance("cached", "queued", "127.0.0.1", 8080, false));
            com.alibaba.cloud.nacos.discovery.ServiceCache.setInstances("queued", instances);
            com.alibaba.cloud.nacos.discovery.ServiceCache.setServiceIds(List.of("cached-service"));
            var subscription = client.getInstances("queued").subscribe(); subscription.dispose();
            var services = client.getServices().subscribe(); services.dispose();
            release.countDown(); var drained = new CountDownLatch(1); scheduler.schedule(drained::countDown); await(drained);
            client.destroy(); assertThat(source.calls).hasValue(0);
            assertThat(com.alibaba.cloud.nacos.discovery.ServiceCache.getInstances("queued")).containsExactlyElementsOf(instances);
            assertThat(com.alibaba.cloud.nacos.discovery.ServiceCache.getServiceIds()).containsExactly("cached-service");
        } finally { release.countDown(); Schedulers.resetFrom(snapshot); scheduler.dispose(); }
    }
    @Test void cancellingAnActiveQueryDoesNotInterruptIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var source = new Source() {
            @Override public List<ServiceInstance> getInstances(String id) throws NacosException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException error) { interrupted.set(true); }
                finally { finished.countDown(); }
                return super.getInstances(id);
            }
        };
        var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var subscription = client.getInstances("example").subscribe(); await(entered); subscription.dispose();
        assertThat(finished.await(100,TimeUnit.MILLISECONDS)).isFalse();
        release.countDown(); await(finished); client.destroy();
        assertThat(interrupted).isFalse(); assertThat(source.calls).hasValue(1);
    }
    @Test void cancellingAnActiveServiceListDoesNotInterruptIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new AtomicBoolean();
        var source = new Source() {
            @Override public List<String> getServices() {
                entered.countDown(); try { release.await(); } catch (InterruptedException error) { interrupted.set(true); }
                return super.getServices();
            }
        };
        var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var subscription = client.getServices().subscribe(); await(entered); subscription.dispose(); release.countDown(); client.destroy();
        assertThat(interrupted).isFalse(); assertThat(source.calls).hasValue(1);
    }
    @Test void shutdownWaitsForAnActiveCancelledQueryAndRejectsNewQueries() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var source = new Source() {
            @Override public List<ServiceInstance> getInstances(String id) throws NacosException {
                entered.countDown(); await(release); return super.getInstances(id);
            }
        };
        var client = new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var subscription = client.getInstances("example").subscribe(); await(entered); subscription.dispose();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var closing=executor.submit(() -> { client.destroy(); return true; });
            assertThatThrownBy(() -> closing.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); assertThat(closing.get(3,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(client.getInstances("another").collectList().block(TIMEOUT)).isEmpty();
        assertThat(source.calls).hasValue(1);
    }
    @Test void shutdownDoesNotLetQueuedQueriesStartAfterItReturns() throws Exception {
        var scheduler=Schedulers.newSingle("closing-discovery-test");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var source=new Source();
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var snapshot = useDiscoveryScheduler(scheduler);
        try {
            scheduler.schedule(() -> { entered.countDown();await(release); });await(entered);
            var done=new CountDownLatch(1);client.getInstances("example").doFinally(signal -> done.countDown()).subscribe();
            client.destroy();release.countDown();await(done);assertThat(source.calls).hasValue(0);
        } finally { release.countDown();Schedulers.resetFrom(snapshot);scheduler.dispose(); }
    }
    @Test void shutdownReportsItsDeadlineInsteadOfWaitingIndefinitely() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var source=new Source(){@Override public List<ServiceInstance> getInstances(String id) throws NacosException {
            entered.countDown();await(release);return super.getInstances(id);
        }};
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,Duration.ofMillis(100));
        var subscription=client.getInstances("example").subscribe();await(entered);subscription.dispose();
        try { assertThatThrownBy(client::destroy).isInstanceOf(IllegalStateException.class).hasMessageContaining("shutdown deadline"); }
        finally { release.countDown(); }
    }
    @Test void concurrentCancelledQueriesAllFinishWithoutInterruptingTheSdk() throws Exception {
        int count=8;var entered=new CountDownLatch(count);var release=new CountDownLatch(1);var interrupted=new AtomicInteger();
        var source=new Source(){@Override public List<ServiceInstance> getInstances(String id) throws NacosException {
            entered.countDown();try{release.await();}catch(InterruptedException error){interrupted.incrementAndGet();}return super.getInstances(id);
        }};
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        var subscriptions=new java.util.ArrayList<reactor.core.Disposable>();
        for(int i=0;i<count;i++)subscriptions.add(client.getInstances("example-"+i).subscribe());
        await(entered);subscriptions.forEach(reactor.core.Disposable::dispose);release.countDown();client.destroy();
        assertThat(source.calls).hasValue(count);assertThat(interrupted).hasValue(0);
    }
    @Test void rejectsUnboundedShutdownTimeouts() {
        for(Duration value:List.of(Duration.ZERO,Duration.ofSeconds(-1)))
            assertThatThrownBy(() -> new CancellationSafeNacosReactiveDiscoveryClient(new Source(),value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cancelledDiscoveryErrorsRemainLoggedAndDoNotBecomeSuccessfulResults() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(
                com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();boolean additive=logger.isAdditive();logger.setAdditive(false);logger.addAppender(appender);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var source=new Source(){@Override public List<ServiceInstance> getInstances(String id) throws NacosException {
            entered.countDown();await(release);throw new NacosException(500,"controlled discovery failure");
        }};
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        try {
            var subscription=client.getInstances("unavailable").subscribe();await(entered);subscription.dispose();
            release.countDown();client.destroy();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("from nacos error");
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo("controlled discovery failure");
            });
        } finally { release.countDown();logger.detachAppender(appender);logger.setAdditive(additive);appender.stop(); }
    }
    @Test void cancelledUncheckedErrorsAreStillVisibleWithoutAnActiveSubscriber() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CancellationSafeNacosReactiveDiscoveryClient.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();boolean additive=logger.isAdditive();logger.setAdditive(false);logger.addAppender(appender);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var source=new Source(){@Override public List<ServiceInstance> getInstances(String id) {
            entered.countDown();await(release);throw new IllegalStateException("controlled failure");
        }};
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        try {
            var subscription=client.getInstances("unavailable").subscribe();await(entered);subscription.dispose();
            release.countDown();client.destroy();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("failed after cancellation", "IllegalStateException");
            });
        } finally {release.countDown();logger.detachAppender(appender);logger.setAdditive(additive);appender.stop();}
    }
    @Test void uncheckedFailuresStillReachTheActiveSubscriberAndReleaseShutdown() throws Exception {
        var source=new Source(){@Override public List<ServiceInstance> getInstances(String id) {throw new IllegalStateException("controlled failure");}};
        var client=new CancellationSafeNacosReactiveDiscoveryClient(source,TIMEOUT);
        assertThatThrownBy(() -> client.getInstances("example").collectList().block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class).hasMessage("controlled failure");
        client.destroy();
    }

}
