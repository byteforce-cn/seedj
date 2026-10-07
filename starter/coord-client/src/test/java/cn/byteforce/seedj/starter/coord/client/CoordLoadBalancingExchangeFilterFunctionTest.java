package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.circuitbreaker.CircuitBreakerClient;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordRegistryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordLoadBalancingExchangeFilterFunction} 语义测试（reactive 形态，C6）。
 */
class CoordLoadBalancingExchangeFilterFunctionTest {

    private final Registry registry = mock(Registry.class);

    private CoordDiscoveryClient discoveryClient() {
        CoordRegistryProperties.Discovery discovery = new CoordRegistryProperties.Discovery();
        discovery.setCacheEnabled(false);
        return new CoordDiscoveryClient(registry, discovery);
    }

    private CoordLoadBalancingExchangeFilterFunction filter(CoordCircuitBreakerReporter reporter) {
        return new CoordLoadBalancingExchangeFilterFunction(
                new RoundRobinCoordLoadBalancer(discoveryClient()), reporter);
    }

    private static void stubService(Registry registry, String name, String address) {
        when(registry.discover(name)).thenReturn(List.of(
                new ServiceInstance("i-1", name, "{\"address\":\"" + address + "\"}")));
    }

    @Test
    void rewritesServiceNameUrl() {
        stubService(registry, "orders-service", "10.0.0.7:9090");
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction next = request -> {
            captured.set(request);
            return Mono.just(mock(ClientResponse.class));
        };
        ClientRequest request = ClientRequest
                .create(HttpMethod.GET, URI.create("http://orders-service/api/x?a=1")).build();

        filter(null).filter(request, next).block();

        assertThat(captured.get().url().getHost()).isEqualTo("10.0.0.7");
        assertThat(captured.get().url().getPort()).isEqualTo(9090);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/x");
        assertThat(captured.get().url().getQuery()).isEqualTo("a=1");
    }

    @Test
    void passesThroughAbsoluteHosts() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction next = request -> {
            captured.set(request);
            return Mono.just(mock(ClientResponse.class));
        };
        ClientRequest request = ClientRequest
                .create(HttpMethod.GET, URI.create("http://api.example.com/x")).build();

        filter(null).filter(request, next).block();

        assertThat(captured.get().url().getHost()).isEqualTo("api.example.com");
    }

    @Test
    void reportsTransportOutcome() {
        stubService(registry, "orders-service", "10.0.0.7:9090");
        CircuitBreakerClient breakerClient = mock(CircuitBreakerClient.class);
        CoordCircuitBreakerReporter reporter = new CoordCircuitBreakerReporter(breakerClient);
        ClientResponse ok = mock(ClientResponse.class);
        when(ok.statusCode()).thenReturn(HttpStatusCode.valueOf(200));
        ClientRequest request = ClientRequest
                .create(HttpMethod.GET, URI.create("http://orders-service/x")).build();

        filter(reporter).filter(request, ignored -> Mono.just(ok)).block();
        verify(breakerClient).reportSuccess(eq("orders-service"));

        assertThatThrownBy(() -> filter(reporter)
                .filter(request, ignored -> Mono.error(new IllegalStateException("boom")))
                .block())
                .hasMessageContaining("boom");
        verify(breakerClient).reportFailure(eq("orders-service"));
    }

    @Test
    void errorsWhenNoInstance() {
        when(registry.discover("ghost-service")).thenReturn(List.of());
        ClientRequest request = ClientRequest
                .create(HttpMethod.GET, URI.create("http://ghost-service/x")).build();

        assertThatThrownBy(() -> filter(null).filter(request, ignored -> Mono.empty()).block())
                .isInstanceOf(CoordNoAvailableInstanceException.class);
    }
}
