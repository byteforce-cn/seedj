package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.clusterEndpoint;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 真集群上的锁语义：两个独立 provider 实例（同 namespace）竞争同一把锁。
 *
 * <p>过期用例直接写入一条已过期记录，而不是 sleep 到过期：真集群的首次 KV/事务往返可能达到秒级（safe_ts 追赶）， 用睡眠断言「恰好还没过期」只会制造抖动。
 */
class CoordLockClusterTest {

    @Test
    void mutualExclusionAndExpiryTakeoverOnRealAgent() {
        try (CoordCluster cluster = CoordCluster.start()) {
            LockProvider first = provider(cluster, "instance-a");
            LockProvider second = provider(cluster, "instance-b");
            first.acquire("cluster:warmup", Duration.ofSeconds(5)).close();

            LockLease lease = first.acquire("cluster:order:1", Duration.ofSeconds(30));
            assertThat(second.tryAcquire("cluster:order:1", Duration.ofSeconds(30))).isEmpty();

            lease.close();
            LockLease taken = second.acquire("cluster:order:1", Duration.ofSeconds(30));
            taken.close();

            seedExpiredRecord(cluster, "cluster:order:2");
            assertThat(second.tryAcquire("cluster:order:2", Duration.ofSeconds(30))).isPresent();
        }
    }

    private static void seedExpiredRecord(CoordCluster cluster, String key) {
        byte[] record =
                CoordJson.write(
                        new CoordLockProvider.LockRecord(
                                "dead-instance", "dead-lease", System.currentTimeMillis() - 1_000));
        cluster.client()
                .kv()
                .put(CoordKeySpace.bytes("seedj:cluster-test:coord:lock:" + key), record);
    }

    private static LockProvider provider(CoordCluster cluster, String holderId) {
        CoordClient client = cluster.client();
        return new CoordLockProvider(
                new CoordKvStore(client.kv(), client.tx()),
                clusterEndpoint(cluster),
                CoordKeySpace.of("seedj:cluster-test", "lock"),
                holderId,
                Clock.systemUTC());
    }
}
