package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.registry.Registration;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.seedj.starter.coord.client.CoordHttpServiceClientFactory;
import cn.byteforce.seedj.starter.coord.client.CoordNoAvailableInstanceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 客户端负载均衡 + 声明式 HTTP 集成验证（Phase 3，C6）：真实注册实例（两台本机
 * HTTP 服务）→ 服务名 URL 经 {@link CoordHttpServiceClientFactory} 代理分布到两端；
 * 无实例时 fail loud。
 */
@SpringBootTest(properties = {
        "spring.application.name=it-client",
        "byteforce.coord.registry.discovery.cache-ttl=30s"
})
class CoordClientIntegrationTest extends AbstractCoordIntegrationTest {

    /** 声明式接口 host 固定（编译期），因此使用固定服务名。 */
    private static final String SERVICE_NAME = "it-lb-demo";

    private static HttpServer serverA;
    private static HttpServer serverB;

    @Autowired
    private Registry registry;

    @Autowired
    private CoordHttpServiceClientFactory clientFactory;

    @BeforeAll
    static void startServers() throws IOException {
        serverA = startServer("A");
        serverB = startServer("B");
    }

    @AfterAll
    static void stopServers() {
        if (serverA != null) {
            serverA.stop(0);
        }
        if (serverB != null) {
            serverB.stop(0);
        }
    }

    private static HttpServer startServer(String identity) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = identity.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void balancesAcrossRegisteredInstances() {
        int portA = serverA.getAddress().getPort();
        int portB = serverB.getAddress().getPort();
        Registration instanceA = registry.register(SERVICE_NAME, "i-a",
                "{\"address\":\"127.0.0.1:" + portA + "\"}", 30);
        Registration instanceB = registry.register(SERVICE_NAME, "i-b",
                "{\"address\":\"127.0.0.1:" + portB + "\"}", 30);
        try {
            EchoClient client = clientFactory.restClient(EchoClient.class);
            List<String> responses = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                responses.add(client.echo());
            }
            // 轮询（缓存窗口内实例列表稳定）：两端都应被命中
            assertThat(responses).contains("A", "B");
        } finally {
            instanceA.close();
            instanceB.close();
        }
    }

    @Test
    void failsLoudWhenNoInstanceRegistered() {
        GhostClient client = clientFactory.restClient(GhostClient.class);

        assertThatThrownBy(client::echo)
                .isInstanceOf(CoordNoAvailableInstanceException.class)
                .hasMessageContaining("ghost-service");
    }

    /** 声明式 HTTP 接口：host = 服务名（由负载均衡改写为实例地址）。 */
    @HttpExchange
    interface EchoClient {

        @GetExchange("http://it-lb-demo/echo")
        String echo();
    }

    /** 无实例场景接口。 */
    @HttpExchange
    interface GhostClient {

        @GetExchange("http://ghost-service/echo")
        String echo();
    }
}
