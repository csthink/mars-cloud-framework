package com.mars.cloud.feign.internal;

import feign.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ResponseBodies {

    private ResponseBodies() {
    }

    static byte[] read(Response response) throws IOException {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream input = response.body().asInputStream()) {
            return input.readAllBytes();
        }
    }

    static boolean isNoInstanceResponse(Response response, byte[] body, String clientName) {
        if (response.status() != 503 || body.length == 0 || clientName == null) {
            return false;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.equals("Load balancer does not contain an instance for the service " + clientName);
    }
}
