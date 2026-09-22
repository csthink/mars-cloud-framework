package com.mars.cloud.observability;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** 对指定端口发匿名或带 Basic 凭据的请求，不让错误状态抛异常。 */
final class ManagementEndpointAccess {

    private ManagementEndpointAccess() {
    }

    static ResponseEntity<String> anonymous(int port, String path) {
        return call(port, path, null, null);
    }

    static ResponseEntity<String> withCredentials(int port, String path, String user, String password) {
        return call(port, path, user, password);
    }

    static HttpStatus status(int port, String path) {
        return HttpStatus.valueOf(anonymous(port, path).getStatusCode().value());
    }

    private static ResponseEntity<String> call(int port, String path, String user, String password) {
        RestClient.RequestHeadersSpec<?> request = RestClient.builder().build()
                .get().uri("http://127.0.0.1:" + port + path);
        if (user != null) {
            request = request.headers(headers -> headers.setBasicAuth(user, password));
        }
        return request.retrieve()
                .onStatus(status -> true, (ignoredRequest, ignoredResponse) -> { })
                .toEntity(String.class);
    }
}
