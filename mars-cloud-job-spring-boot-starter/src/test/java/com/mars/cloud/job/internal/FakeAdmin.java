package com.mars.cloud.job.internal;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的调度中心：只实现执行器会调用的三个开放接口，记录收到的请求。
 *
 * <p>{@link #failWith(int)} 让之后的请求以指定的 code 回失败，{@link #httpStatus(int)} 让它们以指定的 HTTP 状态码回应。
 * {@link #holdRegistrations()} 让注册请求停在处理之前，直到 {@link #releaseRegistrations()}；请求并发处理，
 * 停住的注册不挡其他请求。请求按处理完成的顺序记录。
 */
public final class FakeAdmin implements AutoCloseable {

    public record Received(String path, String token, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger code = new AtomicInteger(Protocol.SUCCESS);
    private final AtomicInteger status = new AtomicInteger(200);
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch registrationHeld = new CountDownLatch(1);
    private volatile CountDownLatch registrationGate;

    public FakeAdmin() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(requests);
        server.createContext("/api/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                CountDownLatch gate = registrationGate;
                if (gate != null && exchange.getRequestURI().getPath().equals("/api/registry")) {
                    registrationHeld.countDown();
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
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

    public void holdRegistrations() {
        registrationGate = new CountDownLatch(1);
    }

    public boolean awaitHeldRegistration(Duration timeout) throws InterruptedException {
        return registrationHeld.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releaseRegistrations() {
        registrationGate.countDown();
    }

    public void recover() {
        code.set(Protocol.SUCCESS);
        status.set(200);
    }

    @Override
    public void close() {
        if (registrationGate != null) {
            registrationGate.countDown();
        }
        server.stop(0);
        requests.close();
    }
}
