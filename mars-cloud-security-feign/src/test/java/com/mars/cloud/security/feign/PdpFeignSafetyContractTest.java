package com.mars.cloud.security.feign;

import com.mars.cloud.security.PdpProtocol;
import com.mars.cloud.security.SecurityFailure;
import feign.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PdpFeignSafetyContractTest {
    interface Api { @RequestLine("POST /upms/v1/decision") String decide(); }
    @Test void finalFeignClientIgnoresRedirectAndVerboseLoggingSettings() {
        var logged = new ArrayList<String>();
        var options = new AtomicReference<Request.Options>();
        var original = new Logger() {
            @Override protected void log(String key, String format, Object... arguments) { logged.add(String.format(format, arguments)); }
        };
        Api api = Feign.builder().logger(original).logLevel(Logger.Level.FULL)
                .options(new Request.Options(1, java.util.concurrent.TimeUnit.SECONDS, 3, java.util.concurrent.TimeUnit.SECONDS, true))
                .requestInterceptor(template -> template.header("Authorization", "Bearer sentinel-access-token"))
                .client((request, finalOptions) -> {
                    options.set(finalOptions);
                    return Response.builder().request(request).status(200).reason("OK")
                            .body("sentinel-downstream-body", java.nio.charset.StandardCharsets.UTF_8).headers(Map.of()).build();
                }).addCapability(new PdpFeignSafetyCapability()).target(Api.class, "http://" + PdpProtocol.SERVICE);
        assertThat(api.decide()).isEqualTo("sentinel-downstream-body");
        assertThat(options.get().isFollowRedirects()).isFalse();
        assertThat(logged).isEmpty();
    }
    @Test void permissionClientNeverSendsToAnotherTarget() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Api api = Feign.builder().client((request, options) -> { calls.incrementAndGet(); throw new AssertionError("Unexpected outbound call"); })
                .addCapability(new PdpFeignSafetyCapability()).target(Api.class, "http://other-service");
        assertThatThrownBy(api::decide).isInstanceOf(SecurityFailure.class);
        assertThat(calls).hasValue(0);
    }
}
