package com.mars.cloud.security.feign;

import com.mars.cloud.feign.*;
import com.mars.cloud.security.*;

/** Does not retain downstream bodies, headers, or transport exceptions. */
public final class PdpFailureMapper implements DownstreamFailureMapper {
    @Override public String clientName() { return PdpProtocol.SERVICE; }
    @Override public RuntimeException map(DownstreamFailure failure) {
        return new SecurityFailure(failure.kind() == DownstreamFailureKind.UNAVAILABLE
                || failure.kind() == DownstreamFailureKind.TIMEOUT
                || Integer.valueOf(503).equals(failure.httpStatus())
                ? SecurityErrorCode.PDP_UNAVAILABLE : SecurityErrorCode.PDP_PROTOCOL_ERROR);
    }
}
