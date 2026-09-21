package com.mars.cloud.security.reactive;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.security.PdpDecision;
import reactor.core.publisher.Mono;

/** Nonblocking permission decision contract. */
public interface ReactivePdpClient {
    Mono<PdpDecision> decide(CallerContext caller, String action, String resource);
}
