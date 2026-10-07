package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.lock.LockClient;
import cn.byteforce.coord.sdk.lock.LockInfo;
import cn.byteforce.seedj.starter.coord.lock.CoordLockLease;
import cn.byteforce.seedj.starter.coord.lock.CoordLockLostListener;
import cn.byteforce.seedj.starter.coord.lock.CoordLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁服务集成验证（Phase 2，C3）：真实协调面下的互斥 / 等待 / 看门狗续约 /
 * 失锁回调（不实现 {@code Lock}、不模拟可重入）。
 */
@SpringBootTest(properties = {
        "spring.application.name=it-lock",
        "byteforce.coord.lock.ttl=3s",
        "byteforce.coord.lock.holder-id=it-holder-main"
})
class CoordLockServiceIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private CoordLockService lockService;

    @Autowired
    private LockClient rawLockClient;

    @Autowired
    private LostLeaseCollector collector;

    @Test
    void mutualExclusionAndRelease() {
        String name = uniqueName("it-lock");
        CoordLockLease lease = lockService.acquire(name).orElseThrow();
        try {
            assertThat(lease.isValid()).isTrue();
            assertThat(lease.getHolderId()).isEqualTo("it-holder-main");
            // 非重入 / 互斥：其他持有者拿不到
            LockInfo other = rawLockClient.acquire(name, "it-holder-other", 30);
            assertThat(other.isAcquired()).isFalse();
        } finally {
            lease.release();
        }
        // 释放后其他持有者可获取（等待窗口轮询，规避时间窗抖动）
        awaitUntil("释放后他人可获取", Duration.ofSeconds(10), () -> {
            LockInfo other = rawLockClient.acquire(name, "it-holder-other", 30);
            if (other.isAcquired()) {
                rawLockClient.release(name, "it-holder-other", other.getLeaseId());
                return true;
            }
            return false;
        });
    }

    @Test
    void waitWindowAcquiresOnceHolderReleases() throws InterruptedException {
        String name = uniqueName("it-lock-wait");
        LockInfo blocker = rawLockClient.acquire(name, "it-holder-other", 30);
        assertThat(blocker.isAcquired()).isTrue();
        try {
            // 等待窗口内先取不到；由后台线程延时释放后获取成功
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                rawLockClient.release(name, "it-holder-other", blocker.getLeaseId());
            });
            releaser.start();
            CoordLockLease lease = lockService.acquire(name, Duration.ofSeconds(15)).orElseThrow();
            lease.release();
            releaser.join(3_000);
        } finally {
            rawLockClient.release(name, "it-holder-other", blocker.getLeaseId());
        }
    }

    @Test
    void watchdogKeepsLeaseAliveBeyondTtl() {
        String name = uniqueName("it-lock-wd");
        CoordLockLease lease = lockService.acquire(name).orElseThrow();
        try {
            // TTL=3s，关闭看门狗续约则必然过期；等待超过 TTL 后租约仍有效即证续约生效
            sleep(4_500);
            assertThat(lease.isValid()).isTrue();
            assertThat(rawLockClient.getLockInfo(name).isAcquired()).isTrue();
        } finally {
            lease.release();
        }
    }

    @Test
    void externalReleaseTriggersLostCallback() {
        String name = uniqueName("it-lock-lost");
        CoordLockLease lease = lockService.acquire(name).orElseThrow();

        // 外部释放（模拟租约被回收 / 易主）：看门狗下一次续约失败 → 失锁回调
        rawLockClient.release(name, lease.getHolderId(), lease.getLeaseId());

        awaitUntil("失锁回调触发", Duration.ofSeconds(15), () -> collector.lost().contains(lease));
        assertThat(lease.isValid()).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LostListenerConfiguration {

        @Bean
        LostLeaseCollector lostLeaseCollector() {
            return new LostLeaseCollector();
        }
    }

    /** 失锁事件收集器。 */
    public static class LostLeaseCollector implements CoordLockLostListener {

        private final List<CoordLockLease> lost = new CopyOnWriteArrayList<>();

        @Override
        public void onLockLost(CoordLockLease lease) {
            lost.add(lease);
        }

        public List<CoordLockLease> lost() {
            return List.copyOf(lost);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
