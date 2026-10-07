package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.cache.CacheClient;
import cn.byteforce.coord.sdk.config.ConfigClient;
import cn.byteforce.coord.sdk.health.HealthStatus;
import cn.byteforce.coord.sdk.mq.MqClient;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.starter.coord.CoordHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter-coord 装配 + 健康检查对真实 Agent 的集成验证。
 *
 * <p>验证「业务服务引入 starter 后直接可用」的最短链路：子客户端按类型可注入、
 * 启动协商成功（{@code await-connected-timeout} 在上下文启动时真实生效）、
 * Actuator 健康指示器返回 UP（单测中断言 DOWN 的对照面）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordStarterIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private CoordClient coordClient;

    @Autowired
    private Registry registry;

    @Autowired
    private ConfigClient configClient;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private MqClient mqClient;

    @Autowired
    private WorkflowClient workflowClient;

    @Autowired
    private TransitClient transitClient;

    @Autowired
    private CoordHealthIndicator coordHealthIndicator;

    @Test
    void bindsFeatureClientsAgainstRealAgent() {
        assertThat(coordClient).isNotNull();
        assertThat(registry).isNotNull();
        assertThat(configClient).isNotNull();
        assertThat(cacheClient).isNotNull();
        assertThat(mqClient).isNotNull();
        assertThat(workflowClient).isNotNull();
        assertThat(transitClient).isNotNull();
    }

    @Test
    void startupNegotiationSucceeded() {
        // 上下文已按 await-connected-timeout=30s 完成启动协商；再做一次显式校验，
        // 防止配置被误改回惰性模式后用例静默失去验证意义。
        coordClient.awaitConnected(Duration.ofSeconds(10));
        assertThat(coordClient.healthCheck()).isEqualTo(HealthStatus.SERVING);
        assertThat(coordClient.agentProtocolVersions()).isNotEmpty();
    }

    @Test
    void healthIndicatorReportsUp() {
        Health health = coordHealthIndicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
