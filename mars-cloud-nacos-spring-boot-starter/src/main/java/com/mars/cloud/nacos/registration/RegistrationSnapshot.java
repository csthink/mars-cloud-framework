package com.mars.cloud.nacos.registration;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable identity used to remove exactly the instance that was attempted. */
public record RegistrationSnapshot(String namespace, String group, String service, String cluster,
        String ip, int port, float weight, boolean enabled, Map<String, String> metadata) {
    public RegistrationSnapshot { metadata = Map.copyOf(metadata); }
    public static RegistrationSnapshot from(NacosRegistration registration, int businessPort) {
        var p = registration.getNacosDiscoveryProperties();
        RegistrationSettings.validate(p);
        return new RegistrationSnapshot(p.getNamespace(), p.getGroup(), registration.getServiceId(),
                registration.getCluster(), registration.getHost(), businessPort,
                registration.getRegisterWeight(), p.isInstanceEnabled(), registration.getMetadata());
    }
    Map<String, String> identity() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("namespaceId", namespace); values.put("groupName", group);
        values.put("serviceName", service); values.put("clusterName", cluster);
        values.put("ip", ip); values.put("port", Integer.toString(port));
        return values;
    }
}
