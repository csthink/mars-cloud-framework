package com.mars.cloud.security;

import com.mars.cloud.common.context.*;
import com.mars.cloud.security.reactive.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;

class ReactiveContextContractTest {
    private static final CallerContext ALICE = new CallerContext("alice", "client", "default");
    private static final CallerContext BOB = new CallerContext("bob", "client", "default");
    @Test void cancellationAndErrorDoNotLeaveIdentityInAnotherSubscription() {
        var stream = Flux.interval(Duration.ofMillis(1)).flatMap(index -> ReactiveCallerContext.current())
                .contextWrite(context -> context.put(CallerContext.class, ALICE));
        StepVerifier.create(stream).expectNext(ALICE).thenCancel().verify();
        StepVerifier.create(ReactiveCallerContext.current()).expectError(SecurityFailure.class).verify();
        StepVerifier.create(ReactiveCallerContext.current().flatMap(caller -> Mono.error(new IllegalStateException("expected")))
                .contextWrite(context -> context.put(CallerContext.class, ALICE))).expectError(IllegalStateException.class).verify();
        StepVerifier.create(ReactiveCallerContext.current()).expectError(SecurityFailure.class).verify();
        assertThat(CallerContextHolder.current()).isEmpty();
    }
    @Test void nestedContextRestoresOuterIdentityAcrossSchedulerChanges() {
        var identities = ReactiveCallerContext.current().flatMapMany(outer -> Flux.concat(
                ReactiveCallerContext.current().subscribeOn(Schedulers.parallel()).contextWrite(context -> context.put(CallerContext.class, BOB)),
                ReactiveCallerContext.current())).contextWrite(context -> context.put(CallerContext.class, ALICE));
        StepVerifier.create(identities).expectNext(BOB, ALICE).verifyComplete();
    }
}
