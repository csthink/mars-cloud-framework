package com.mars.cloud.security.feign;

import com.mars.cloud.security.AuthenticatedCaller;
import feign.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;

/** Client-local token propagation. This class must not be component-scanned. */
public class PdpFeignConfiguration {
    @Bean PdpFeignSafetyCapability marsPdpSafetyCapability() { return new PdpFeignSafetyCapability(); }
    @Bean RequestInterceptor marsPdpBearerToken() {
        return template -> {
            var token = AuthenticatedCaller.token(SecurityContextHolder.getContext().getAuthentication());
            template.removeHeader("Authorization");
            template.header("Authorization", "Bearer " + token.getToken().getTokenValue());
        };
    }
    @Bean("marsFeignRequestOptions") Request.Options marsPdpOptions() { return new Request.Options(1, TimeUnit.SECONDS, 3, TimeUnit.SECONDS, false); }
}
