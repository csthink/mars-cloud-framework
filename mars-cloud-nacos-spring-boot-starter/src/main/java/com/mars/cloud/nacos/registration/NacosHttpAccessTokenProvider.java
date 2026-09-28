package com.mars.cloud.nacos.registration;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class NacosHttpAccessTokenProvider {
    private final NacosHttpTransport transport;
    private String token;
    private long expires;
    private String username;
    private String password;
    public NacosHttpAccessTokenProvider(NacosHttpTransport transport) { this.transport=transport; }
    public String get(URI server, String username, String password, long deadline, BooleanSupplier allowed) {
        if (!username.equals(this.username) || !password.equals(this.password)) invalidate();
        if (token!=null && System.nanoTime()<expires) return token;
        var response=transport.call(server,"auth/user/login","POST",Map.of("username",username,"password",password),null,deadline,allowed);
        var body=response.body();
        if (response.httpStatus()!=200 || body==null || !body.hasNonNull("accessToken") || body.path("tokenTtl").asLong()<=0)
            throw new NacosHttpTransport.Failure(false,response.httpStatus());
        token=body.path("accessToken").asString();
        if (token==null || token.isBlank()) throw new NacosHttpTransport.Failure(false,response.httpStatus());
        long ttl=Math.min(body.path("tokenTtl").asLong(),86400);
        expires=System.nanoTime()+Duration.ofSeconds(Math.max(1,ttl- Math.min(60,ttl/5))).toNanos();
        this.username=username; this.password=password;
        return token;
    }
    public void invalidate() { token=null; expires=0; }
}
