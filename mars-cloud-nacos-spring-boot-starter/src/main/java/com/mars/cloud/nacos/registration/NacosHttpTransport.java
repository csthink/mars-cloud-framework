package com.mars.cloud.nacos.registration;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Single-attempt transport, isolated from discovery and application HTTP clients. */
public final class NacosHttpTransport implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BoundedOperation worker = new BoundedOperation("nacos-registration-http");
    private final CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries()
            .disableRedirectHandling().disableCookieManagement().disableAuthCaching()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setMaxConnTotal(1).setMaxConnPerRoute(1)
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(1))
                            .setSocketTimeout(Timeout.ofSeconds(2)).build()).build())
            .setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false)
                    .setConnectionRequestTimeout(Timeout.ofSeconds(1)).setResponseTimeout(Timeout.ofSeconds(2)).build()).build();
    private volatile HttpUriRequestBase current;
    public record Response(int httpStatus, JsonNode body) { }
    public static final class Failure extends RuntimeException {
        private final boolean uncertain;
        private final int status;
        private final CompletableFuture<Response> outcome;
        Failure(boolean uncertain, int status) { this(uncertain,status,null); }
        Failure(boolean uncertain,int status,CompletableFuture<Response> outcome) {
            super("Nacos HTTP operation failed; status="+status+", remote_state_unknown="+uncertain);
            this.uncertain=uncertain; this.status=status; this.outcome=outcome;
        }
        public boolean uncertain() { return uncertain; }
        public int status() { return status; }
        public CompletableFuture<Response> outcome() { return outcome; }
    }
    public Response call(URI base, String path, String method, Map<String,String> values, String token,
            long deadline, BooleanSupplier allowed) {
        long limit=Math.min(deadline,System.nanoTime()+Duration.ofSeconds(2).toNanos());
        if (System.nanoTime()>=limit || worker.busy() || !allowed.getAsBoolean()) throw new Failure(false,0);
        String encoded=values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(Collectors.joining("&"));
        URI uri=base.resolve(path+(method.equals("POST") ? "" : "?"+encoded));
        HttpUriRequestBase request=new HttpUriRequestBase(method,uri);
        if (method.equals("POST")) request.setEntity(new StringEntity(encoded,ContentType.APPLICATION_FORM_URLENCODED));
        if (token!=null) request.setHeader("accessToken",token);
        AtomicBoolean attempted=new AtomicBoolean();
        CompletableFuture<Response> outcome=new CompletableFuture<>();
        try {
            return worker.call(()->{
                if (System.nanoTime()>=limit || !allowed.getAsBoolean() || request.isCancelled()) throw new Failure(false,0);
                current=request;
                try {
                    attempted.set(true);
                    Response result=client.execute(request,response->{
                        byte[] bytes;
                        try (InputStream input=response.getEntity()==null ? InputStream.nullInputStream() : response.getEntity().getContent()) {
                            bytes=input.readNBytes(65537);
                        }
                        if (bytes.length>65536) throw new Failure(true,response.getCode());
                        if (response.getCode()!=200) return new Response(response.getCode(), null);
                        JsonNode body=JSON.readTree(bytes);
                        return new Response(response.getCode(),body);
                    });
                    outcome.complete(result);
                    return result;
                } finally { current=null; }
            },limit,request::cancel);
        } catch (Failure failure) { throw failure; }
        catch (Exception failure) { throw new Failure(attempted.get(),0,outcome); }
    }
    public static String json(Object value) { return JSON.writeValueAsString(value); }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    public void cancel() { var request=current; if(request!=null) request.cancel(); }
    @Override public void close() { cancel(); client.close(CloseMode.IMMEDIATE); worker.close(); }
}
