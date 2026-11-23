package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderContractTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * 契约 TCK：31 个用例跑在真 coord 集群（v0.4.6）上。
 *
 * <p>集群开认证：coord 0.4.5 的 {@code on_behalf_of} 只在调用方主体非 {@code anonymous} 时可用，
 * 而 seedj 的席位动作全部靠服务代终端用户。每个实现用例用独立 base namespace 隔离定义/实例。
 */
class CoordWorkflowProviderContractTest extends WorkflowProviderContractTest {

    private static CoordCluster cluster;

    @BeforeAll
    static void startCluster() {
        cluster = CoordCluster.startWithAuth();
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Override
    protected WorkflowProvider createProvider() {
        CoordWorkflowProperties properties = new CoordWorkflowProperties();
        properties.setSignAdmin("root");
        return new CoordWorkflowProvider(
                cluster.client().workflow(),
                cluster.client().kv(),
                properties,
                "contract-" + UUID.randomUUID());
    }
}
