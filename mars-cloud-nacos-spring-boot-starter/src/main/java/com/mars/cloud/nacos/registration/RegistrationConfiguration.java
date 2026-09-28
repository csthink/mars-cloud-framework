package com.mars.cloud.nacos.registration;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosRegistrationCustomizer;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/** Builds an independent configuration value instead of reading a bean during in-place rebinding. */
public final class RegistrationConfiguration {
    private final ApplicationContext context;
    private final List<NacosRegistrationCustomizer> customizers;
    private final String detectedIp;
    public RegistrationConfiguration(ApplicationContext context, List<NacosRegistrationCustomizer> customizers, String detectedIp) {
        this.context=context; this.customizers=List.copyOf(customizers); this.detectedIp=detectedIp;
    }
    public static final class Value {
        final RegistrationSnapshot snapshot;
        final List<URI> servers;
        final String username;
        final String password;
        final boolean enabled;
        Value(RegistrationSnapshot snapshot,List<URI> servers,String username,String password,boolean enabled) {
            this.snapshot=snapshot; this.servers=List.copyOf(servers); this.username=username; this.password=password; this.enabled=enabled;
        }
        boolean same(Value other) { return snapshot.equals(other.snapshot) && servers.equals(other.servers)
                && username.equals(other.username) && password.equals(other.password) && enabled==other.enabled; }
    }
    public Value read(int port) {
        Environment env=context.getEnvironment();
        NacosDiscoveryProperties properties=Binder.get(env).bind("spring.cloud.nacos.discovery",NacosDiscoveryProperties.class)
                .orElseGet(NacosDiscoveryProperties::new);
        if (properties.getServerAddr()==null) properties.setServerAddr(env.getProperty("spring.cloud.nacos.server-addr"));
        if (properties.getUsername()==null) properties.setUsername(env.getProperty("spring.cloud.nacos.username"));
        if (properties.getPassword()==null) properties.setPassword(env.getProperty("spring.cloud.nacos.password"));
        if (properties.getService()==null) properties.setService(env.getProperty("spring.application.name"));
        if (properties.getIp()==null) properties.setIp(detectedIp);
        if (properties.getClusterName()==null) properties.setClusterName("DEFAULT");
        if (properties.getPort() < -1 || (port>0 && properties.getPort()>0 && properties.getPort()!=port))
            throw new IllegalStateException("Discovery port must match the actual business web server port");
        properties.setPort(port);
        if (properties.isSecure()) properties.getMetadata().put("secure","true");
        properties.getMetadata().put(com.alibaba.nacos.api.naming.PreservedMetadataKeys.REGISTER_SOURCE,"SPRING_CLOUD");
        boolean enabled=properties.isRegisterEnabled() && env.getProperty("spring.cloud.service-registry.auto-registration.enabled",Boolean.class,true);
        validateConsumerSettings(env);
        RegistrationSettings.validate(properties);
        if (properties.getService()==null || properties.getService().isBlank() || properties.getIp()==null || properties.getIp().isBlank())
            throw new IllegalStateException("Registration requires service name and business IP");
        NacosRegistration registration=new NacosRegistration(customizers,properties,context);
        registration.init();
        RegistrationSnapshot snapshot=RegistrationSnapshot.from(registration,port);
        return new Value(snapshot,RegistrationSettings.addresses(properties.getServerAddr()),properties.getUsername(),properties.getPassword(),enabled);
    }
    private static void validateConsumerSettings(Environment env) {
        String local=Binder.get(env).bind("spring.cloud.nacos.discovery.naming-push-empty-protection",String.class).orElse("false");
        if (java.util.Set.of("true","on","yes","y","t").contains(local.toLowerCase(java.util.Locale.ROOT))
                || com.alibaba.nacos.client.env.NacosClientProperties.PROTOTYPE.getBoolean("namingPushEmptyProtection",false))
            throw new IllegalStateException("Nacos empty instance protection must remain disabled");
        var builtin=java.util.Set.of("com.alibaba.nacos.client.auth.impl.NacosClientAuthServiceImpl",
                "com.alibaba.nacos.client.auth.ram.RamClientAuthServiceImpl");
        boolean custom=java.util.ServiceLoader.load(com.alibaba.nacos.plugin.auth.spi.client.AbstractClientAuthService.class)
                .stream().anyMatch(provider->!builtin.contains(provider.type().getName()));
        if (custom) throw new IllegalStateException("Custom Nacos authentication plugins are unsupported for registration");
    }
}
