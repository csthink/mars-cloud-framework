package com.mars.cloud.nacos.registration;

import java.time.Duration;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.registry.ReactiveHealthContributorRegistry;

/** Evaluates the complete group outside web event loops, without overlapping stalled checks. */
public final class NacosReadinessEvaluator implements AutoCloseable {
    private final HealthEndpoint endpoint;
    private final HealthEndpointGroups groups;
    private final HealthContributorRegistry blocking;
    private final ReactiveHealthContributorRegistry reactive;
    private final ApplicationAvailability availability;
    private final BoundedOperation checks = new BoundedOperation("nacos-registration-health");
    public NacosReadinessEvaluator(HealthEndpoint endpoint, HealthEndpointGroups groups,
            HealthContributorRegistry blocking, ReactiveHealthContributorRegistry reactive, ApplicationAvailability availability) {
        this.endpoint=endpoint; this.groups=groups; this.blocking=blocking; this.reactive=reactive; this.availability=availability;
        validateGroups();
    }
    public void validateGroups() {
        require("liveness", "livenessState"); require("readiness", "readinessState");
    }
    private void require(String group, String member) {
        if (groups.get(group)==null || !groups.get(group).isMember(member)
                || ((blocking==null || blocking.getContributor(member)==null)
                && (reactive==null || reactive.getContributor(member)==null)))
            throw new IllegalStateException("Nacos registration requires health group " + group + " with " + member);
    }
    public boolean accepting() { return availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC; }
    public boolean ready(long deadline) {
        if (!accepting() || checks.busy()) return false;
        try {
            validateGroups();
            return checks.call(()-> {
                var health=endpoint.healthForPath("readiness");
                return health!=null && Status.UP.equals(health.getStatus());
            }, Math.min(deadline,System.nanoTime()+Duration.ofSeconds(1).toNanos()), ()->{}) && accepting();
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Readiness check failed: {}", failure.getClass().getSimpleName());
            return false;
        }
    }
    @Override public void close() { checks.close(); }
}
