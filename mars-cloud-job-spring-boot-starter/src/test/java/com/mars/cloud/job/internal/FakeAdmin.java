package com.mars.cloud.job.internal;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的调度中心：只实现执行器会调用的三个开放接口，记录收到的请求。
 *
 * <p>{@link #failWith(int)} 让之后的请求以指定的 code 回失败，{@link #httpStatus(int)} 让它们以指定的 HTTP 状态码回应。
 */
public final class FakeAdmin implements AutoCloseable {

    public record Received(String path, String token, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger code = new AtomicInteger(Protocol.SUCCESS);
    private final AtomicInteger status = new AtomicInteger(200);

    public FakeAdmin() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst(Protocol.ACCESS_TOKEN_HEADER), body));
                if (status.get() != 200) {
                    exchange.sendResponseHeaders(status.get(), -1);
                    return;
                }
                byte[] json = Protocol.write(new Protocol.Response(code.get(), code.get() == Protocol.SUCCESS ? null : "rejected", null));
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** 已送达的全部回调，按到达顺序展开。 */
    public List<Protocol.CallbackRequest> callbacks() {
        return received("/api/callback").stream()
                .flatMap(r -> Protocol.readCallbacks(r.body().getBytes(StandardCharsets.UTF_8)).stream())
                .toList();
    }

    public void failWith(int failureCode) {
        code.set(failureCode);
    }

    public void httpStatus(int httpStatus) {
        status.set(httpStatus);
    }

    public void recover() {
        code.set(Protocol.SUCCESS);
        status.set(200);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
