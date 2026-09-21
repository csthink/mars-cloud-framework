package com.mars.cloud.security.feign;

import com.mars.cloud.security.*;
import feign.*;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** Enforces permission-client redirect and logging constraints after Feign property overrides. */
public final class PdpFeignSafetyCapability implements Capability {
    @Override public Logger enrich(Logger logger) { return new Logger.NoOpLogger(); }
    @Override public Client enrich(Client client) {
        return (request, options) -> {
            URI uri = URI.create(request.url());
            if (!PdpProtocol.SERVICE.equals(uri.getHost()) || uri.getUserInfo() != null
                    || !PdpProtocol.PATH.equals(uri.getPath()) || uri.getQuery() != null
                    || request.httpMethod() != Request.HttpMethod.POST)
                throw new SecurityFailure(SecurityErrorCode.PDP_PROTOCOL_ERROR);
            var bounded = new Request.Options(Math.min(options.connectTimeoutMillis(), 1000), TimeUnit.MILLISECONDS,
                    Math.min(options.readTimeoutMillis(), 3000), TimeUnit.MILLISECONDS, false);
            return client.execute(request, bounded);
        };
    }
}
