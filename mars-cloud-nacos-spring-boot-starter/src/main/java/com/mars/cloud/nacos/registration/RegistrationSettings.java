package com.mars.cloud.nacos.registration;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.naming.PreservedMetadataKeys;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class RegistrationSettings {
    private RegistrationSettings() { }
    public static void validate(NacosDiscoveryProperties p) {
        if (!p.isEphemeral() || present(p.getEndpoint()) || present(p.getAccessKey()) || present(p.getSecretKey()))
            throw new IllegalStateException("HTTP registration requires ephemeral instances and explicit server-addr with username/password authentication");
        if (!present(p.getNamespace()) || !"DEFAULT_GROUP".equals(p.getGroup())
                || !present(p.getUsername()) || !present(p.getPassword()))
            throw new IllegalStateException("HTTP registration requires namespace, DEFAULT_GROUP and username/password");
        if (p.getHeartBeatInterval()!=null || p.getHeartBeatTimeout()!=null || p.getIpDeleteTimeout()!=null)
            throw new IllegalStateException("Custom heartbeat timing is not supported");
        for (String key : List.of(PreservedMetadataKeys.HEART_BEAT_INTERVAL, PreservedMetadataKeys.HEART_BEAT_TIMEOUT,
                PreservedMetadataKeys.IP_DELETE_TIMEOUT)) {
            if (p.getMetadata().containsKey(key)) throw new IllegalStateException("Custom heartbeat metadata is not supported");
        }
        if (!Float.isFinite(p.getWeight()) || p.getWeight()<0 || p.getWeight()>10000)
            throw new IllegalStateException("Invalid discovery weight");
        addresses(p.getServerAddr());
    }
    public static List<URI> addresses(String value) {
        try {
            if (!present(value)) throw new IllegalArgumentException();
            return Arrays.stream(value.split(",", -1)).map(String::trim).map(raw -> {
                URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                        || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                        || uri.getPort()==0 || uri.getPort() < -1 || uri.getPort()>65535) throw new IllegalArgumentException();
                String path = uri.getPath();
                if (path==null || path.isEmpty() || path.equals("/")) path="/nacos";
                else if (path.endsWith("/")) path=path.substring(0,path.length()-1);
                if (path.contains("..") || uri.getRawPath().contains("%")) throw new IllegalArgumentException();
                String authority=uri.getRawAuthority();
                if (!raw.contains("://") && uri.getPort()==-1) authority+=":8848";
                return URI.create(uri.getScheme()+"://"+authority+path+"/v3/");
            }).toList();
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid Nacos HTTP server-addr"); }
    }
    private static boolean present(String value) { return value!=null && !value.isBlank(); }
}
