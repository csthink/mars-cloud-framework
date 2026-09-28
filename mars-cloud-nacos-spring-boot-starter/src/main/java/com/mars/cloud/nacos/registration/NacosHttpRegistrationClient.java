package com.mars.cloud.nacos.registration;

import java.net.URI;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class NacosHttpRegistrationClient implements AutoCloseable {
    private final NacosHttpTransport transport;
    public NacosHttpRegistrationClient(NacosHttpTransport transport) { this.transport=transport; }
    public void register(URI server, String token, RegistrationSnapshot snapshot, long deadline, BooleanSupplier allowed) {
        Map<String,String> values=snapshot.identity();
        values.put("ephemeral","true"); values.put("heartBeat","false");
        values.put("weight",Float.toString(snapshot.weight())); values.put("enabled",Boolean.toString(snapshot.enabled()));
        values.put("metadata",NacosHttpTransport.json(snapshot.metadata()));
        success(transport.call(server,"client/ns/instance","POST",values,token,deadline,allowed));
    }
    public boolean renew(URI server,String token,RegistrationSnapshot snapshot,long deadline,BooleanSupplier allowed) {
        Map<String,String> values=snapshot.identity(); values.put("heartBeat","true");
        var response=transport.call(server,"client/ns/instance","POST",values,token,deadline,allowed);
        if (response.httpStatus()==200 && response.body()!=null && response.body().path("code").asInt()==21003) return false;
        success(response); return true;
    }
    public void remove(URI server,String token,RegistrationSnapshot snapshot,long deadline) {
        Map<String,String> values=snapshot.identity(); values.put("ephemeral","true");
        success(transport.call(server,"client/ns/instance","DELETE",values,token,deadline,()->true));
    }
    /** Consumer visibility only; disabled instances are also omitted by this endpoint.
     * The caller must require successful DELETE and no unresolved registration before recovery. */
    public boolean absent(URI server,String token,RegistrationSnapshot snapshot,long deadline) {
        Map<String,String> values=snapshot.identity(); values.remove("ip"); values.remove("port"); values.put("healthyOnly","false");
        var response=transport.call(server,"client/ns/instance/list","GET",values,token,deadline,()->true);
        if (response.httpStatus()==200 && response.body()!=null && response.body().path("code").asInt()==21003) return true;
        success(response);
        var rows=response.body().path("data");
        if (!rows.isArray()) throw new NacosHttpTransport.Failure(true,response.httpStatus());
        for (var row:rows) if (row.path("ip").asString().equals(snapshot.ip()) && row.path("port").asInt()==snapshot.port()
                && row.path("clusterName").asString().equals(snapshot.cluster())) return false;
        return true;
    }
    private static void success(NacosHttpTransport.Response response) {
        if (response.httpStatus()==200 && response.body()!=null && response.body().path("code").isIntegralNumber() && response.body().path("code").asInt()==0) return;
        int http=response.httpStatus();
        throw new NacosHttpTransport.Failure(!(http==401 || http==403 || (http>=300 && http<400)),http);
    }
    @Override public void close() { transport.close(); }
}
