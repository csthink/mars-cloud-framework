package com.mars.cloud.feign.internal;

record EnvelopeInspection(boolean envelope, boolean success, String code) {

    static EnvelopeInspection malformed() {
        return new EnvelopeInspection(false, false, null);
    }
}
