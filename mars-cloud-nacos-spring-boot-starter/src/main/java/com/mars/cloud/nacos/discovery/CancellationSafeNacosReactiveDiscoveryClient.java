package com.mars.cloud.nacos.discovery;

import com.alibaba.cloud.nacos.discovery.NacosServiceDiscovery;
import com.alibaba.cloud.nacos.discovery.reactive.NacosReactiveDiscoveryClient;
import com.alibaba.nacos.api.exception.NacosException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cloud.client.ServiceInstance;
import reactor.core.publisher.Flux;

/** Keeps an in-progress synchronous discovery call independent of subscriber cancellation. */
public final class CancellationSafeNacosReactiveDiscoveryClient extends NacosReactiveDiscoveryClient implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(CancellationSafeNacosReactiveDiscoveryClient.class);
    private final NacosServiceDiscovery discovery;
    private final Consumer<NacosReactiveDiscoveryClient> configure;
    private final Duration shutdownTimeout;
    private final Object lifecycle = new Object();
    private boolean closed;
    private int active;

    public CancellationSafeNacosReactiveDiscoveryClient(NacosServiceDiscovery discovery, Duration shutdownTimeout) {
        this(discovery, shutdownTimeout, client -> {});
    }

    public CancellationSafeNacosReactiveDiscoveryClient(NacosServiceDiscovery discovery, Duration shutdownTimeout,
            Consumer<NacosReactiveDiscoveryClient> configure) {
        super(discovery);
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero())
            throw new IllegalArgumentException("Discovery shutdown timeout must be positive");
        this.discovery = discovery;
        this.shutdownTimeout = shutdownTimeout;
        this.configure = configure;
    }

    @Override public Flux<ServiceInstance> getInstances(String serviceId) {
        return retain(client -> client.getInstances(serviceId));
    }

    @Override public Flux<String> getServices() {
        return retain(NacosReactiveDiscoveryClient::getServices);
    }

    private <T> Flux<T> retain(Function<NacosReactiveDiscoveryClient, Flux<T>> query) {
        return Flux.defer(() -> {
            QueryState state = new QueryState();
            NacosServiceDiscovery guarded = new NacosServiceDiscovery(null, null) {
                @Override public List<ServiceInstance> getInstances(String id) throws NacosException {
                    return state.start() ? discovery.getInstances(id) : null;
                }
                @Override public List<String> getServices() throws NacosException {
                    return state.start() ? discovery.getServices() : null;
                }
            };
            // The original client keeps its own scheduling, configured fallback and error handling.
            // A skipped source returns null: SCA completes without writing an empty list to ServiceCache.
            NacosReactiveDiscoveryClient delegate = new NacosReactiveDiscoveryClient(guarded);
            configure.accept(delegate);
            var owned = query.apply(delegate).collectList().doOnError(error -> {
                synchronized (lifecycle) {
                    if (state.cancelled) LOG.error("Nacos discovery query failed after cancellation: {}", error.getClass().getSimpleName());
                }
            }).doFinally(signal -> state.finish()).cache();
            // This cache is created per subscription and only retains that query until it finishes.
            return owned.doOnCancel(state::cancel).flatMapMany(Flux::fromIterable);
        });
    }

    private final class QueryState {
        private boolean cancelled;
        private boolean started;
        boolean start() {
            synchronized (lifecycle) {
                if (cancelled || closed) return false;
                started = true;
                active++;
                return true;
            }
        }
        void cancel() {
            synchronized (lifecycle) { cancelled = true; }
        }
        void finish() {
            synchronized (lifecycle) {
                if (started) { active--; lifecycle.notifyAll(); }
            }
        }
    }

    @Override public void destroy() throws InterruptedException {
        long budget;
        try { budget = shutdownTimeout.toNanos(); }
        catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
        long start = System.nanoTime();
        synchronized (lifecycle) {
            closed = true;
            while (active > 0) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) throw new IllegalStateException("Discovery calls did not finish before shutdown deadline");
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
            }
        }
    }
}
