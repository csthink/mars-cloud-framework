package com.mars.cloud.core.resilience;

import java.util.Map;
import org.springframework.context.support.DefaultLifecycleProcessor;

/** Lifecycle processor that keeps every shutdown phase within thirty seconds. */
public class BoundedLifecycleProcessor extends DefaultLifecycleProcessor {
    @Override
    public void setTimeoutPerShutdownPhase(long timeout) {
        validate(timeout);
        super.setTimeoutPerShutdownPhase(timeout);
    }

    @Override
    public void setTimeoutForShutdownPhase(int phase, long timeout) {
        validate(timeout);
        super.setTimeoutForShutdownPhase(phase, timeout);
    }

    @Override
    public void setTimeoutsForShutdownPhases(Map<Integer, Long> timeouts) {
        timeouts.values().forEach(BoundedLifecycleProcessor::validate);
        super.setTimeoutsForShutdownPhases(timeouts);
    }

    private static void validate(long timeout) {
        if (timeout <= 0 || timeout > 30_000) {
            throw new IllegalArgumentException("Shutdown phase timeout must be between 1 and 30000 milliseconds");
        }
    }
}
