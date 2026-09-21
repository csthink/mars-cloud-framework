package com.mars.cloud.security;

import org.springframework.security.access.AccessDeniedException;

/** Contains only a stable error code; downstream exceptions and tokens are not retained. */
public final class SecurityFailure extends AccessDeniedException {
    private final SecurityErrorCode code;
    public SecurityFailure(SecurityErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }
    public SecurityErrorCode code() { return code; }
    public static SecurityErrorCode find(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SecurityFailure failure) return failure.code();
        }
        return SecurityErrorCode.ACCESS_DENIED;
    }
}
