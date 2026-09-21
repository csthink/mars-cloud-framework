package com.mars.cloud.security;

import com.mars.cloud.common.context.CallerContext;

/** Blocking permission decision contract. */
public interface PdpClient {
    PdpDecision decide(CallerContext caller, String action, String resource);
}
