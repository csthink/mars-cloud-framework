package com.mars.cloud.security.feign;

import com.mars.cloud.security.PdpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

/** Optional Servlet adapter, registered only when permission calls are enabled. */
@AutoConfiguration(beforeName = "com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"feign.Feign", "jakarta.servlet.Filter"})
@ConditionalOnProperty(prefix = "mars.security.authorization", name = "enabled", matchIfMissing = true)
@EnableFeignClients(clients = PermissionDecisionApi.class)
public class SecurityFeignAutoConfiguration {
    @Bean @ConditionalOnMissingBean(PdpClient.class)
    PdpClient marsFeignPdpClient(PermissionDecisionApi api) { return new FeignPdpClient(api); }
    @Bean PdpFailureMapper marsPdpFailureMapper() { return new PdpFailureMapper(); }
}
