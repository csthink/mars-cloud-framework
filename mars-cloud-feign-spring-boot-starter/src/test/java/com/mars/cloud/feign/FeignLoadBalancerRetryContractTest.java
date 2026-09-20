package com.mars.cloud.feign;

import feign.Client;
import com.mars.cloud.feign.internal.ServiceInstanceRetryGuard;
import com.mars.cloud.feign.internal.MarsFeignComponents;
import com.mars.cloud.feign.internal.DownstreamFailureMapperRegistry;
import tools.jackson.databind.json.JsonMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryPolicy;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.ServiceInstanceChooser;
import org.springframework.cloud.loadbalancer.blocking.retry.BlockingLoadBalancedRetryPolicy;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class FeignLoadBalancerRetryContractTest {

    private static final Request.Options OPTIONS = new Request.Options(1000, 3000);

    @Test
    void getRetriesOnceOnTheNextServiceInstance() throws Exception {
        RetryFixture fixture = retryFixture();

        Response response = fixture.client().execute(request(Request.HttpMethod.GET), OPTIONS);

        assertThat(response.status()).isEqualTo(200);
        assertThat(fixture.selectedInstances()).containsExactly("instance-1", "instance-2");
        assertThat(fixture.requestedHosts()).containsExactly("first.internal", "second.internal");
    }

    @Test
    void postDoesNotRetry() throws Exception {
        RetryFixture fixture = retryFixture();

        Response response = fixture.client().execute(request(Request.HttpMethod.POST), OPTIONS);

        assertThat(response.status()).isEqualTo(503);
        assertThat(fixture.selectedInstances()).containsExactly("instance-1");
        assertThat(fixture.requestedHosts()).containsExactly("first.internal");
    }

    @Test
    void singleInstanceFailureDoesNotSendASecondRequest() {
        RetryFixture fixture = retryFixture(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fixture.client().execute(request(Request.HttpMethod.GET), OPTIONS))
                .isInstanceOf(IllegalStateException.class).hasMessage("UNAVAILABLE");
        assertThat(fixture.requestedHosts()).containsExactly("first.internal");
    }

    private static RetryFixture retryFixture() {
        return retryFixture(false);
    }

    private static RetryFixture retryFixture(boolean sameInstance) {
        ServiceInstance first = new DefaultServiceInstance(
                "instance-1", "orders", "first.internal", 8081, false);
        ServiceInstance second = new DefaultServiceInstance(
                "instance-2", "orders", "second.internal", 8082, false);
        List<ServiceInstance> instances = List.of(first, second);
        AtomicInteger selection = new AtomicInteger();
        List<String> selectedInstances = new ArrayList<>();
        List<String> requestedHosts = new ArrayList<>();

        LoadBalancerProperties properties = new LoadBalancerProperties();
        properties.getRetry().setEnabled(true);
        properties.getRetry().setMaxRetriesOnSameServiceInstance(0);
        properties.getRetry().setMaxRetriesOnNextServiceInstance(1);
        properties.getRetry().setRetryOnAllOperations(false);
        properties.getRetry().setRetryableStatusCodes(Set.of(503));

        LoadBalancerClient loadBalancer = mock(LoadBalancerClient.class);
        when(loadBalancer.choose(eq("orders"), any(org.springframework.cloud.client.loadbalancer.Request.class)))
                .thenAnswer(invocation -> {
                    ServiceInstance chosen = instances.get(sameInstance ? 0 : Math.min(selection.getAndIncrement(), 1));
                    selectedInstances.add(chosen.getInstanceId());
                    return chosen;
                });
        when(loadBalancer.reconstructURI(any(ServiceInstance.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    ServiceInstance instance = invocation.getArgument(0);
                    URI original = invocation.getArgument(1);
                    return URI.create(instance.getUri() + original.getRawPath());
                });

        LoadBalancerClientFactory clientFactory = mock(LoadBalancerClientFactory.class);
        when(clientFactory.getProperties("orders")).thenReturn(properties);
        when(clientFactory.getInstances(eq("orders"), any())).thenReturn(Map.of());

        LoadBalancedRetryFactory retryFactory = new LoadBalancedRetryFactory() {
            @Override
            public LoadBalancedRetryPolicy createRetryPolicy(
                    String serviceId, ServiceInstanceChooser serviceInstanceChooser) {
                return new BlockingLoadBalancedRetryPolicy(properties);
            }
        };

        Client delegate = (request, options) -> {
            String host = URI.create(request.url()).getHost();
            requestedHosts.add(host);
            int status = "first.internal".equals(host) ? 503 : 200;
            return Response.builder()
                    .status(status)
                    .reason(status == 200 ? "OK" : "Service Unavailable")
                    .request(request)
                    .headers(Map.of())
                    .body(new byte[0])
                    .build();
        };

        Client client = new RetryableFeignBlockingLoadBalancerClient(
                delegate, loadBalancer, retryFactory, clientFactory, List.of(new ServiceInstanceRetryGuard()));
        DownstreamFailureMapper mapper = new DownstreamFailureMapper() {
            public String clientName() { return "orders"; }
            public RuntimeException map(DownstreamFailure failure) {
                return new IllegalStateException(failure.kind().name());
            }
        };
        client = new MarsFeignComponents(new DownstreamFailureMapperRegistry(List.of(mapper)),
                JsonMapper.builder().build()).client(client);
        return new RetryFixture(client, selectedInstances, requestedHosts);
    }

    private static Request request(Request.HttpMethod method) {
        return Request.create(
                method,
                "http://orders/resource",
                Map.of(),
                method == Request.HttpMethod.POST ? "{}".getBytes(StandardCharsets.UTF_8) : null,
                StandardCharsets.UTF_8,
                new RequestTemplate());
    }

    private record RetryFixture(
            Client client,
            List<String> selectedInstances,
            List<String> requestedHosts) {
    }
}
