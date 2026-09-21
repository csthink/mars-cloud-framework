package com.mars.cloud.security.feign;

import tools.jackson.databind.JsonNode;
import com.mars.cloud.security.PdpProtocol;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** Internal, service-discovered permission protocol. */
@FeignClient(name = PdpProtocol.SERVICE, contextId = "marsSecurityPdp", configuration = PdpFeignConfiguration.class)
public interface PermissionDecisionApi {
    @PostMapping(PdpProtocol.PATH)
    JsonNode decide(@RequestBody PdpProtocol.Request request);
}
