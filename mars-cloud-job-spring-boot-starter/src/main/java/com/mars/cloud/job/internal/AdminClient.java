package com.mars.cloud.job.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用调度中心的三个开放接口：{@code /api/registry}、{@code /api/registryRemove}、{@code /api/callback}。
 *
 * <p>按配置顺序逐个尝试调度中心地址，第一个返回 {@code code == 200} 的即成功；全部失败时返回各地址的失败原因。
 */
public final class AdminClient {

    private final List<URI> admins;
    private final String accessToken;
    private final Duration timeout;
    private final HttpClient http;

    public AdminClient(List<URI> admins, String accessToken, Duration timeout) {
        this.admins = List.copyOf(admins);
        this.accessToken = accessToken;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    /** 调用结果；失败时 {@code detail} 写明每个地址的原因。 */
    public record Outcome(boolean succeeded, String detail) {
    }

    public Outcome registry(Protocol.RegistryRequest request) {
        return post("/api/registry", request);
    }

    public Outcome registryRemove(Protocol.RegistryRequest request) {
        return post("/api/registryRemove", request);
    }

    public Outcome callback(List<Protocol.CallbackRequest> results) {
        return post("/api/callback", results);
    }

    private Outcome post(String path, Object body) {
        byte[] json = Protocol.write(body);
        List<String> failures = new ArrayList<>();
        for (URI admin : admins) {
            URI target = URI.create(admin + path);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header(Protocol.ACCESS_TOKEN_HEADER, accessToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    failures.add(target + " 返回 HTTP " + response.statusCode());
                    continue;
                }
                Protocol.Response result = Protocol.read(response.body(), Protocol.Response.class);
                if (result.succeeded()) {
                    return new Outcome(true, null);
                }
                failures.add(target + " 返回 code=" + result.code() + " msg=" + result.msg());
            } catch (IOException | IllegalArgumentException e) {
                failures.add(target + " 调用失败：" + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(target + " 调用被中断");
                break;
            }
        }
        return new Outcome(false, String.join("；", failures));
    }
}
