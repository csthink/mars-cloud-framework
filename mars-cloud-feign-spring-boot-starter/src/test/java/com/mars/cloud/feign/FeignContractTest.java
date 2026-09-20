package com.mars.cloud.feign;

import com.mars.cloud.common.context.CallerContext;
import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.feign.internal.CallerContextRequestInterceptor;
import com.mars.cloud.feign.internal.DownstreamFailureMapperRegistry;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import feign.codec.Decoder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeignContractTest {

    @Test
    void identityInterceptorReplacesSpoofedHeadersAndClearsWhenContextIsAbsent() {
        CallerContextRequestInterceptor interceptor = new CallerContextRequestInterceptor();
        RequestTemplate template = new RequestTemplate();
        template.header(InternalCallHeaders.SUBJECT, "spoofed");
        template.header(InternalCallHeaders.CLIENT_ID, "spoofed");
        template.header(InternalCallHeaders.TENANT_ID, "spoofed");

        interceptor.apply(template);
        assertThat(template.headers()).doesNotContainKeys(
                InternalCallHeaders.SUBJECT,
                InternalCallHeaders.CLIENT_ID,
                InternalCallHeaders.TENANT_ID);

        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(
                new CallerContext("subject-1", "client-1", "tenant-1"))) {
            interceptor.apply(template);
        }

        assertThat(template.headers().get(InternalCallHeaders.SUBJECT)).containsExactly("subject-1");
        assertThat(template.headers().get(InternalCallHeaders.CLIENT_ID)).containsExactly("client-1");
        assertThat(template.headers().get(InternalCallHeaders.TENANT_ID)).containsExactly("tenant-1");
    }

    @Test
    void successEnvelopeDelegatesWithoutChangingBody() {
        AtomicReference<Request> captured = new AtomicReference<>();
        TestClient client = clientReturning(200, "{\"success\":true,\"result\":{\"value\":1}}", captured, failure -> mapped(failure));

        assertThat(client.value()).isEqualTo("{\"success\":true,\"result\":{\"value\":1}}");
        assertThat(captured.get().url()).isEqualTo("http://inventory/value");
    }

    @Test
    void businessFailureEnvelopeUsesCallerMapperWithoutLeakingMessage() {
        TestClient client = clientReturning(
                200,
                "{\"success\":false,\"code\":\"denied\",\"message\":\"private detail\"}",
                new AtomicReference<>(),
                FeignContractTest::mapped);

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> {
                    DownstreamFailure failure = ((MappedFailureException) ex).failure;
                    assertThat(failure.kind()).isEqualTo(DownstreamFailureKind.BUSINESS_ENVELOPE);
                    assertThat(failure.downstreamCode()).isEqualTo("denied");
                    assertThat(failure.toString()).doesNotContain("private detail");
                });
    }

    @Test
    void non2xxFailureEnvelopeKeepsStatusAndCode() {
        TestClient client = clientReturning(
                400,
                "{\"success\":false,\"code\":\"bad_input\",\"message\":\"private detail\"}",
                new AtomicReference<>(),
                FeignContractTest::mapped);

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> {
                    DownstreamFailure failure = ((MappedFailureException) ex).failure;
                    assertThat(failure.kind()).isEqualTo(DownstreamFailureKind.HTTP);
                    assertThat(failure.httpStatus()).isEqualTo(400);
                    assertThat(failure.downstreamCode()).isEqualTo("bad_input");
                });
    }

    @Test
    void malformedSuccessResponseFailsClosed() {
        TestClient client = clientReturning(200, "not-json", new AtomicReference<>(), FeignContractTest::mapped);

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> assertThat(((MappedFailureException) ex).failure.kind())
                        .isEqualTo(DownstreamFailureKind.MALFORMED_RESPONSE));
    }

    @Test
    void loadBalancerNoInstanceResponseMapsToUnavailable() {
        TestClient client = clientReturning(
                503,
                "Load balancer does not contain an instance for the service inventory",
                new AtomicReference<>(),
                FeignContractTest::mapped);

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> assertThat(((MappedFailureException) ex).failure.kind())
                        .isEqualTo(DownstreamFailureKind.UNAVAILABLE));
    }

    @Test
    void transportTimeoutMapsAfterClientFailure() {
        TestClient client = clientThrowing(new SocketTimeoutException("timed out"));

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> assertThat(((MappedFailureException) ex).failure.kind())
                        .isEqualTo(DownstreamFailureKind.TIMEOUT));
    }

    @Test
    void connectionFailureMapsToUnavailable() {
        TestClient client = clientThrowing(new ConnectException("refused"));

        assertThatThrownBy(client::value)
                .isInstanceOf(MappedFailureException.class)
                .satisfies(ex -> assertThat(((MappedFailureException) ex).failure.kind())
                        .isEqualTo(DownstreamFailureKind.UNAVAILABLE));
    }

    @Test
    void responseBodyReadTimeoutUsesCallerMapper() {
        Client transport = (request, options) -> Response.builder().request(request).status(200)
                .reason("OK").body(new java.io.InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new SocketTimeoutException("body timeout");
                    }
                }, null).build();
        TestClient client = buildClient(transport, FeignContractTest::mapped);
        assertThatThrownBy(client::value).isInstanceOfSatisfying(MappedFailureException.class,
                ex -> assertThat(ex.failure.kind()).isEqualTo(DownstreamFailureKind.TIMEOUT));
    }

    @Test
    void finalTransportRemovesHeadersAddedAfterIdentityInterceptor() {
        AtomicReference<Request> captured = new AtomicReference<>();
        Client transport = (request, options) -> {
            captured.set(request);
            return Response.builder().request(request).status(200).reason("OK")
                    .body("{\"success\":true}", StandardCharsets.UTF_8).build();
        };
        MarsFeignCapability capability = new MarsFeignCapability(
                new DownstreamFailureMapperRegistry(List.of()), JsonMapper.builder().build());
        TestClient client = Feign.builder().client(transport).retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(1000, 3000)).decoder(new feign.codec.StringDecoder())
                .requestInterceptor(template -> template.header("x-mars-subject", "untrusted"))
                .addCapability(capability)
                .target(new Target.HardCodedTarget<>(TestClient.class, "inventory", "http://inventory"));
        client.value();
        assertThat(captured.get().headers().keySet()).noneMatch(key -> key.equalsIgnoreCase("X-Mars-Subject"));
        try (CallerContextHolder.Scope ignored = CallerContextHolder.open(
                new CallerContext("subject-1", "client-1", "default"))) {
            client.value();
            assertThat(captured.get().headers().get(InternalCallHeaders.SUBJECT)).containsExactly("subject-1");
        }
    }

    @Test
    void dtoDecodeFailureIsSanitizedAndMapped() {
        MarsFeignCapability capability = new MarsFeignCapability(
                new DownstreamFailureMapperRegistry(List.of(mapper("inventory", FeignContractTest::mapped))),
                JsonMapper.builder().build());
        Client transport = (request, options) -> Response.builder().request(request).status(200).reason("OK")
                .body("{\"success\":true,\"result\":\"private detail\"}", StandardCharsets.UTF_8).build();
        TestClient client = Feign.builder().client(transport).retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(1000, 3000))
                .decoder((response, type) -> { throw new IllegalArgumentException("private detail"); })
                .addCapability(capability)
                .target(new Target.HardCodedTarget<>(TestClient.class, "inventory", "http://inventory"));
        assertThatThrownBy(client::value).isInstanceOfSatisfying(MappedFailureException.class, ex -> {
            assertThat(ex.failure.kind()).isEqualTo(DownstreamFailureKind.MALFORMED_RESPONSE);
            assertThat(ex.failure.cause()).isNull();
            assertThat(ex).hasStackTraceContaining("MALFORMED_RESPONSE").hasMessageNotContaining("private detail");
        });
    }

    @Test
    void mapperIsMandatory() {
        TestClient client = clientReturning(200, "{\"success\":false,\"code\":\"denied\"}",
                new AtomicReference<>(), null);

        assertThatIllegalStateException()
                .isThrownBy(client::value)
                .withMessageContaining("inventory")
                .withMessageContaining("缺少");
    }

    @Test
    void duplicateMapperIsRejectedAtStartup() {
        DownstreamFailureMapper first = mapper("inventory", FeignContractTest::mapped);
        DownstreamFailureMapper second = mapper("inventory", FeignContractTest::mapped);

        assertThatIllegalStateException()
                .isThrownBy(() -> new DownstreamFailureMapperRegistry(List.of(first, second)))
                .withMessageContaining("只能声明一个");
    }

    @Test
    void nullMapperResultFailsClosed() {
        TestClient client = clientReturning(200, "{\"success\":false,\"code\":\"denied\"}",
                new AtomicReference<>(), failure -> null);

        assertThatIllegalStateException()
                .isThrownBy(client::value)
                .withMessageContaining("返回了 null");
    }

    private static TestClient clientReturning(int status,
                                              String body,
                                              AtomicReference<Request> captured,
                                              FailureFunction mapper) {
        Client transport = (request, options) -> {
            captured.set(request);
            return Response.builder()
                    .request(request)
                    .status(status)
                    .reason("test")
                    .body(body, StandardCharsets.UTF_8)
                    .build();
        };
        return buildClient(transport, mapper);
    }

    private static TestClient clientThrowing(IOException failure) {
        Client transport = (request, options) -> {
            throw failure;
        };
        return buildClient(transport, FeignContractTest::mapped);
    }

    private static TestClient buildClient(Client transport, FailureFunction mapperFunction) {
        List<DownstreamFailureMapper> mappers = mapperFunction == null
                ? List.of()
                : List.of(mapper("inventory", mapperFunction));
        MarsFeignCapability capability = new MarsFeignCapability(
                new DownstreamFailureMapperRegistry(mappers),
                JsonMapper.builder().build());
        Decoder bodyDecoder = (response, type) -> new String(
                response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);

        return Feign.builder()
                .retryer(Retryer.NEVER_RETRY)
                .options(new Request.Options(1000, 3000))
                .client(transport)
                .decoder(bodyDecoder)
                .addCapability(capability)
                .target(new Target.HardCodedTarget<>(TestClient.class, "inventory", "http://inventory"));
    }

    private static DownstreamFailureMapper mapper(String clientName, FailureFunction function) {
        return new DownstreamFailureMapper() {
            @Override
            public String clientName() {
                return clientName;
            }

            @Override
            public RuntimeException map(DownstreamFailure failure) {
                return function.apply(failure);
            }
        };
    }

    private static MappedFailureException mapped(DownstreamFailure failure) {
        return new MappedFailureException(failure);
    }

    private interface FailureFunction {
        RuntimeException apply(DownstreamFailure failure);
    }

    interface TestClient {

        @RequestLine("GET /value")
        String value();
    }

    private static final class MappedFailureException extends RuntimeException {

        private final DownstreamFailure failure;

        private MappedFailureException(DownstreamFailure failure) {
            super(failure.kind().name());
            this.failure = failure;
        }
    }
}
