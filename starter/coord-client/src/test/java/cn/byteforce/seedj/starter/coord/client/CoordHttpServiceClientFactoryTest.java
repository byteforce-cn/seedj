package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.circuitbreaker.CircuitBreakerClient;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordRegistryProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordHttpServiceClientFactory} 端到端测试：真实本机 HTTP 服务 + 真实
 * 声明式代理（{@code HttpServiceProxyFactory}），验证「服务名 → 实例地址」改写与调用链。
 */
class CoordHttpServiceClientFactoryTest {

    private static HttpServer server;
    private static int serverPort;

    private final Registry registry = mock(Registry.class);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> {
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private CoordDiscoveryClient discoveryClient() {
        CoordRegistryProperties.Discovery discovery = new CoordRegistryProperties.Discovery();
        discovery.setCacheEnabled(false);
        return new CoordDiscoveryClient(registry, discovery);
    }

    private void register(String serviceName) {
        when(registry.discover(serviceName)).thenReturn(List.of(
                new ServiceInstance("i-1", serviceName,
                        "{\"address\":\"127.0.0.1:" + serverPort + "\",\"protocol\":\"http\"}")));
    }

    @Test
    void declaresClientResolvesServiceNameAndInvokesRealServer() {
        register("orders-service");
        CoordCircuitBreakerReporter reporter =
                new CoordCircuitBreakerReporter(mock(CircuitBreakerClient.class));
        CoordHttpServiceClientFactory factory = new CoordHttpServiceClientFactory(
                new RoundRobinCoordLoadBalancer(discoveryClient()), reporter);

        HelloClient client = factory.restClient(HelloClient.class);

        assertThat(client.hello()).isEqualTo("hello");
    }

    @Test
    void reportsOutcomeToCircuitBreakerBridge() {
        register("orders-service");
        CircuitBreakerClient breakerClient = mock(CircuitBreakerClient.class);
        CoordHttpServiceClientFactory factory = new CoordHttpServiceClientFactory(
                new RoundRobinCoordLoadBalancer(discoveryClient()),
                new CoordCircuitBreakerReporter(breakerClient));

        factory.restClient(HelloClient.class).hello();

        verify(breakerClient).reportSuccess(eq("orders-service"));
    }

    @Test
    void failsLoudWhenNoInstanceAvailable() {
        when(registry.discover("ghost-service")).thenReturn(List.of());
        CoordHttpServiceClientFactory factory = new CoordHttpServiceClientFactory(
                new RoundRobinCoordLoadBalancer(discoveryClient()), null);

        GhostClient client = factory.restClient(GhostClient.class);

        assertThatThrownBy(client::hello)
                .isInstanceOf(CoordNoAvailableInstanceException.class)
                .hasMessageContaining("ghost-service");
    }

    /** 声明式 HTTP 接口（框架 @HttpExchange；host 为服务名）。 */
    @HttpExchange
    interface HelloClient {

        @GetExchange("http://orders-service/hello")
        String hello();
    }

    /** 失败场景用接口（服务名 ghost-service）。 */
    @HttpExchange
    interface GhostClient {

        @GetExchange("http://ghost-service/hello")
        String hello();
    }
}
