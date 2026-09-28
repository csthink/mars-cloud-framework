package com.mars.cloud.nacos.registration;

import com.alibaba.cloud.nacos.NacosServiceManager;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;

/** Serializes registration, renewal and deletion; discovery remains alive until bean destruction. */
public final class NacosRegistrationLifecycle implements AutoServiceRegistration, GenericApplicationListener, DisposableBean {
    public enum State { WAITING, REGISTERING, REGISTERED, WITHDRAWING, UNCERTAIN, CLOSING, CLOSED }
    private static final Logger LOG=LoggerFactory.getLogger(NacosRegistrationLifecycle.class);
    private final ApplicationContext context;
    private final RegistrationConfiguration configuration;
    private final NacosReadinessEvaluator readiness;
    private final NacosHttpTransport transport;
    private final NacosHttpRegistrationClient client;
    private final NacosHttpAccessTokenProvider tokens;
    private final NacosServiceManager discovery;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("nacos-registration-state").factory());
    private final AtomicBoolean closing=new AtomicBoolean();
    private record Desired(RegistrationConfiguration.Value value, long generation, boolean valid) { }
    private volatile Desired desired;
    private volatile State state=State.WAITING;
    private volatile int port;
    private volatile boolean applicationReady;
    private CompletableFuture<NacosHttpTransport.Response> pendingRegister;
    private RegistrationConfiguration.Value active;
    private URI activeServer;
    private boolean unresolvedRegister;
    private int cleanupAttempts;
    private int failures;
    private int addressIndex;
    private volatile long next;

    public NacosRegistrationLifecycle(ApplicationContext context, RegistrationConfiguration configuration,
            NacosReadinessEvaluator readiness, NacosServiceManager discovery) {
        this.context=context; this.configuration=configuration; this.readiness=readiness; this.discovery=discovery;
        transport=new NacosHttpTransport(); client=new NacosHttpRegistrationClient(transport); tokens=new NacosHttpAccessTokenProvider(transport);
        desired=new Desired(configuration.read(0),0,true);
        scheduler.scheduleWithFixedDelay(this::tickSafely,100,100,TimeUnit.MILLISECONDS);
    }
    public State state() { return state; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }
    @Override public boolean supportsEventType(ResolvableType type) { return true; }
    @Override public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextClosedEvent closed && closed.getApplicationContext()==context) { closeRegistration(); return; }
        if (closing.get()) return;
        if (event instanceof WebServerInitializedEvent web && web.getApplicationContext()==context) {
            port=web.getWebServer().getPort(); refreshConfiguration();
        } else if (event instanceof ApplicationReadyEvent ready && ready.getApplicationContext()==context) {
            readiness.validateGroups(); applicationReady=true;
        } else if (event instanceof AvailabilityChangeEvent<?> availability && availability.getState() instanceof ReadinessState) {
            next=0;
        } else if (event.getClass().getName().equals("org.springframework.cloud.context.environment.EnvironmentChangeEvent")) {
            refreshConfiguration();
        }
    }
    private synchronized void refreshConfiguration() {
        if (closing.get()) return;
        try {
            var replacement=configuration.read(port);
            Desired previous=desired;
            if (!previous.valid() || !previous.value().same(replacement)) desired=new Desired(replacement,previous.generation()+1,true);
            next=0;
        } catch (RuntimeException invalid) {
            desired=new Desired(desired.value(),desired.generation()+1,false);
            next=0;
            throw new IllegalStateException("Invalid Nacos registration configuration; existing snapshot retained");
        }
    }
    private boolean permitted(Desired version) {
        return !closing.get() && version.valid() && applicationReady && port>0 && desired==version && readiness.accepting();
    }
    private void tickSafely() {
        if (closing.get() || System.nanoTime()<next) return;
        try { tick(); }
        catch (RuntimeException failure) { diagnostic("state", false); backoff(); }
    }
    private void tick() {
        long cycleStart=System.nanoTime();
        long deadline=cycleStart+Duration.ofSeconds(6).toNanos();
        collectRegisterResponse();
        Desired version=desired;
        var value=version.value();
        if (active!=null && (!version.valid() || !active.same(value) || state==State.UNCERTAIN || state==State.WITHDRAWING)) {
            if (unresolvedRegister && cleanupAttempts>=3) { next=System.nanoTime()+Duration.ofSeconds(1).toNanos(); return; }
            cleanup(deadline); return;
        }
        if (!value.enabled || !permitted(version)) {
            if (active!=null) cleanup(deadline);
            else next=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            return;
        }
        URI server=value.servers.get(Math.floorMod(addressIndex,value.servers.size()));
        String token;
        try { token=tokens.get(server,value.username,value.password,deadline,()->!closing.get()); }
        catch (NacosHttpTransport.Failure failure) { diagnostic("authentication",false); addressIndex++; backoff(); return; }
        if (!readiness.ready(deadline) || !permitted(version)) {
            if (active!=null) cleanup(deadline);
            else next=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            return;
        }
        boolean registering=active==null;
        if (registering) { active=value; activeServer=server; state=State.REGISTERING; }
        try {
            if (registering) client.register(server,token,value.snapshot,deadline,()->permitted(version));
            else if (!client.renew(server,token,value.snapshot,deadline,()->permitted(version))) {
                active=null; activeServer=null; state=State.WAITING; failures=0;
                next=System.nanoTime()+Duration.ofSeconds(1).toNanos(); return;
            }
            failures=0;
            if (!permitted(version)) { cleanup(deadline); return; }
            state=State.REGISTERED;
            next=cycleStart+Duration.ofSeconds(5).toNanos();
            if (registering) {
                context.publishEvent(new InstanceRegisteredEvent<>(this,value.snapshot));
                LOG.info("Nacos registration confirmed");
            }
        } catch (NacosHttpTransport.Failure failure) {
            if (failure.status()==401 || failure.status()==403) tokens.invalidate();
            if (registering && !failure.uncertain()) { active=null; activeServer=null; state=State.WAITING; }
            else { unresolvedRegister |= registering && failure.uncertain();
                if (registering && failure.uncertain()) pendingRegister=failure.outcome();
                state=State.UNCERTAIN; }
            diagnostic(registering ? "register" : "renew",failure.uncertain()); addressIndex++; backoff();
        }
    }
    private boolean cleanup(long deadline) {
        if (active==null) return true;
        state=closing.get() ? State.CLOSING : State.WITHDRAWING;
        cleanupAttempts++;
        collectRegisterResponse();
        try {
            URI server=active.servers.get(Math.floorMod(addressIndex,active.servers.size()));
            String token=tokens.get(server,active.username,active.password,deadline,()->System.nanoTime()<deadline);
            client.remove(server,token,active.snapshot,deadline);
            if (!client.absent(server,token,active.snapshot,deadline)) { state=State.UNCERTAIN; backoff(); return false; }
            if (unresolvedRegister) {
                state=State.UNCERTAIN; diagnostic("register_quarantined",true); backoff(); return false;
            }
            active=null; activeServer=null; cleanupAttempts=0; failures=0;
            state=closing.get() ? State.CLOSING : State.WAITING;
            next=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            LOG.info("Nacos instance deletion observed"); return true;
        } catch (NacosHttpTransport.Failure failure) {
            if (failure.status()==401 || failure.status()==403) tokens.invalidate();
            state=State.UNCERTAIN; diagnostic("delete",true); addressIndex++; backoff(); return false;
        }
    }
    private void collectRegisterResponse() {
        if (unresolvedRegister && pendingRegister!=null && pendingRegister.isDone()) {
            var response=pendingRegister.getNow(null);
            if (response!=null && ((response.httpStatus()==200 && response.body()!=null && response.body().path("code").isIntegralNumber())
                    || response.httpStatus()==401 || response.httpStatus()==403)) {
                unresolvedRegister=false; pendingRegister=null;
            }
        }
    }
    private void backoff() {
        int seconds=switch (Math.min(failures++,2)) { case 0 -> 1; case 1 -> 2; default -> 5; };
        next=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
    }
    private void diagnostic(String operation,boolean unknown) {
        LOG.warn("Nacos registration operation={} remote_state_unknown={}",operation,unknown);
    }
    public void closeRegistration() {
        if (!closing.compareAndSet(false,true)) return;
        state=State.CLOSING;
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        AvailabilityChangeEvent.publish(context,ReadinessState.REFUSING_TRAFFIC);
        try {
            var completion=scheduler.submit(()-> {
                while (active!=null && System.nanoTime()<deadline) {
                    collectRegisterResponse();
                    if ((!unresolvedRegister || cleanupAttempts<3) && cleanup(deadline)) break;

                    long remaining=deadline-System.nanoTime();
                    if (remaining>0) java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining,Duration.ofMillis(100).toNanos()));
                }
                if (active!=null) diagnostic("close",true);
            });
            completion.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            diagnostic("close",true); if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow(); transport.close(); state=State.CLOSED;
        }
    }
    @Override public void destroy() throws Exception { closeRegistration(); readiness.close(); }
}
