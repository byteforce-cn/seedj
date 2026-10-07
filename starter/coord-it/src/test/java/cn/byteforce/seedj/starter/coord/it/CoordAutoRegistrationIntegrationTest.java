package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import cn.byteforce.seedj.starter.coord.registry.CoordAutoRegistration;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务自动注册 / 优雅注销集成验证（Phase 1，C1）：
 * 真实 web 容器端口 → 注册（metadata schema）→ 发现链路可见 → 子上下文关闭 → 注销收敛。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.application.name=it-registry-auto",
        "byteforce.coord.registry.host=127.0.0.1",
        "byteforce.coord.registry.discovery.cache-enabled=false"
})
class CoordAutoRegistrationIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private Registry registry;

    @Autowired
    private CoordAutoRegistration autoRegistration;

    @Autowired
    private CoordDiscoveryClient discoveryClient;

    @Autowired
    private WebServerApplicationContext webServerContext;

    @Test
    void registersWithMetadataSchemaAndIsVisibleThroughStarterDiscovery() {
        awaitUntil("自动注册完成", Duration.ofSeconds(30), () -> autoRegistration.isRegistered());
        int port = webServerContext.getWebServer().getPort();
        String expectedInstanceId = "127.0.0.1:" + port;

        List<ServiceInstance> found = registry.discover("it-registry-auto");
        ServiceInstance mine = found.stream()
                .filter(instance -> expectedInstanceId.equals(instance.getInstanceId()))
                .findFirst()
                .orElse(null);
        assertThat(mine).as("注册实例应可发现").isNotNull();
        JsonNode metadata = JsonMapper.builder().build().readTree(mine.getMetadata());
        assertThat(metadata.get("address").asString()).isEqualTo(expectedInstanceId);
        assertThat(metadata.get("protocol").asString()).isEqualTo("http");
        assertThat(metadata.get("weight").asInt()).isEqualTo(100);

        // starter 消费链路（发现视图）
        assertThat(discoveryClient.getInstances("it-registry-auto"))
                .extracting(CoordServiceInstance::getInstanceId)
                .contains(expectedInstanceId);
    }

    @Test
    void childContextDeregistersGracefullyOnClose() {
        String serviceName = uniqueName("it-registry-auto-child");
        CoordItContainer container = CoordItContainer.getInstance();

        ConfigurableApplicationContext child = new SpringApplicationBuilder(CoordItApplication.class)
                .properties(Map.of(
                        "spring.application.name", serviceName,
                        "server.port", "0",
                        "byteforce.coord.registry.host", "127.0.0.1",
                        "byteforce.coord.agent-host", container.agentHost(),
                        "byteforce.coord.agent-port", String.valueOf(container.agentPort()),
                        "byteforce.coord.await-connected-timeout", "30s"))
                .run();
        try {
            awaitUntil("子上下文注册完成", Duration.ofSeconds(30),
                    () -> !registry.discover(serviceName).isEmpty());
        } finally {
            child.close();
        }
        awaitUntil("关闭后优雅注销（无残留实例）", Duration.ofSeconds(20),
                () -> registry.discover(serviceName).isEmpty());
    }
}
