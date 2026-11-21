package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import cn.byteforce.seedj.provider.pki.PkiProvider;
import cn.byteforce.seedj.provider.pki.PkiProviderContractTest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * coord PKI 的契约测试（真集群）。
 *
 * <p>放在 seedj-infra-coord 的包内是为了直接用包级实现类；测试跑在 seedj-it 里，因为需要 Docker。
 *
 * <p>隔离靠实例前缀：每个用例一个随机 {@code identity-prefix}，共用同一个 agent 的 CA。
 */
class CoordPkiProviderIT extends PkiProviderContractTest {

    private static CoordCluster cluster;

    @BeforeAll
    static void startCluster() {
        cluster = CoordCluster.start();
        try {
            cluster.client().pki().initCa("seedj-it-ca");
        } catch (Exception e) {
            Assumptions.abort("cannot initialize coord test CA: " + e.getMessage());
        }
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Override
    protected PkiProvider newProvider() {
        String prefix = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new CoordPkiProvider(
                cluster.client().pki(),
                cluster.client().transit(),
                new CoordPkiState(
                        new CoordKvStore(cluster.client().kv(), cluster.client().tx()),
                        CoordKeySpace.of("seedj:it", "pki")),
                new CoordEndpoint(cluster.agentHost() + ":" + cluster.agentPort()),
                prefix,
                Duration.ofHours(24),
                Clock.systemUTC());
    }
}
