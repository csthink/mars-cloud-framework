package com.mars.cloud.job.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 执行器的 HTTP 服务端：{@code POST /beat}、{@code /idleBeat}、{@code /run}、{@code /kill}、{@code /log}。
 *
 * <p>绑定到指定地址（{@code server.address} 是具体 IP 时只监听它），端口占用时 {@link #start()} 直接抛出，应用启动失败。
 * 方法与路径之后、读请求体之前按 {@link Protocol#ACCESS_TOKEN_HEADER} 做常量时间比较，令牌不符时不读请求体，
 * 回 {@code The access token is wrong.}，这句与 xxl-job 执行器原有的提示相同，调度日志里能直接认出。
 * 请求体超过 1 MiB 回 413。
 */
public final class ExecutorHttpServer {

    static final String WRONG_TOKEN = "The access token is wrong.";
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ExecutorHttpServer.class);

    private final InetSocketAddress bindAddress;
    private final byte[] accessToken;
    private final JobDispatcher dispatcher;
    private HttpServer server;
    private ExecutorService requestThreads;

    public ExecutorHttpServer(InetSocketAddress bindAddress, String accessToken, JobDispatcher dispatcher) {
        this.bindAddress = bindAddress;
        this.accessToken = accessToken.getBytes(StandardCharsets.UTF_8);
        this.dispatcher = dispatcher;
    }

    /**
     * @throws IllegalStateException 端口无法绑定
     */
    public void start() {
        try {
            server = HttpServer.create(bindAddress, 0);
        } catch (IOException e) {
            throw new IllegalStateException("执行器端口绑定失败：" + describe(bindAddress) + "，原因：" + e.getMessage(), e);
        }
        requestThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(requestThreads);
        server.createContext("/", this::handle);
        server.start();
    }

    /** 实际监听的端口（配置为随机端口时由绑定结果给出）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 实际绑定的地址与端口。 */
    public InetSocketAddress address() {
        return server.getAddress();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            requestThreads.close();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/beat") && !path.equals("/idleBeat") && !path.equals("/run")
                    && !path.equals("/kill") && !path.equals("/log")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            // 令牌在读请求体之前核对：没有令牌的请求不读正文，不占用读取正文的内存与时间。
            if (!authorized(exchange)) {
                respond(exchange, Protocol.Response.fail(WRONG_TOKEN));
                return;
            }
            byte[] body = readBody(exchange.getRequestBody());
            if (body == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            respond(exchange, dispatch(path, body));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String presented = exchange.getRequestHeaders().getFirst(Protocol.ACCESS_TOKEN_HEADER);
        return presented != null && MessageDigest.isEqual(accessToken, presented.getBytes(StandardCharsets.UTF_8));
    }

    private Protocol.Response dispatch(String path, byte[] body) {
        try {
            return switch (path) {
                case "/beat" -> Protocol.Response.success();
                case "/idleBeat" -> dispatcher.idleBeat(Protocol.read(body, Protocol.IdleBeatRequest.class).jobId());
                case "/run" -> dispatcher.run(Protocol.read(body, Protocol.TriggerRequest.class));
                case "/kill" -> dispatcher.kill(Protocol.read(body, Protocol.KillRequest.class).jobId());
                default -> dispatcher.log(Protocol.read(body, Protocol.LogRequest.class));
            };
        } catch (IllegalArgumentException malformed) {
            return Protocol.Response.fail(malformed.getMessage());
        } catch (RuntimeException unexpected) {
            log.error("执行器处理 {} 请求失败", path, unexpected);
            return Protocol.Response.fail("执行器处理请求失败：" + unexpected);
        }
    }

    private static void respond(HttpExchange exchange, Protocol.Response response) throws IOException {
        byte[] json = Protocol.write(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, json.length);
        // 关闭响应流即发出响应：关闭交换时 JDK 先处理未读的请求体，再冲刷响应，令牌不符的请求会等到正文读完才得到回答。
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(json);
        }
    }

    /** 读取请求体；超过上限时返回 null。 */
    private static byte[] readBody(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static String describe(InetSocketAddress address) {
        return (address.getAddress() == null || address.getAddress().isAnyLocalAddress()
                ? "*" : address.getAddress().getHostAddress()) + ":" + address.getPort();
    }
}
