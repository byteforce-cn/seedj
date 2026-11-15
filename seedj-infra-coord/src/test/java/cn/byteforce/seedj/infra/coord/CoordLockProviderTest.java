package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.lock.LockErrorCode;
import cn.byteforce.seedj.lock.LockException;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.lock.LockProviderContractTest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 亚秒级租约、过期接管与失败映射；真实集群行为另由 {@link CoordLockClusterTest} 守护。 */
class CoordLockProviderTest extends LockProviderContractTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();

    @Override
    protected LockProvider newProvider() {
        return provider("holder-1", Clock.systemUTC());
    }

    private CoordLockProvider provider(String holderId, Clock clock) {
        return new CoordLockProvider(
                new CoordKvStore(backend.kv(), backend.txn()),
                ENDPOINT,
                CoordKeySpace.of("seedj:test", "lock"),
                holderId,
                clock);
    }

    @Test
    @DisplayName("租约按毫秒过期：时钟推进后另一持有者可接管")
    void expiredRecordCanBeTakenOver() {
        MutableClock clock = new MutableClock(1_000_000L);
        CoordLockProvider first = provider("holder-1", clock);
        CoordLockProvider second = provider("holder-2", clock);
        assertThat(first.tryAcquire("order:1", Duration.ofMillis(150))).isPresent();

        clock.advance(Duration.ofMillis(149));
        assertThat(second.tryAcquire("order:1", Duration.ofMillis(150))).isEmpty();

        clock.advance(Duration.ofMillis(2));
        Optional<LockLease> taken = second.tryAcquire("order:1", Duration.ofMillis(150));
        assertThat(taken).isPresent();
        taken.orElseThrow().close();
    }

    @Test
    @DisplayName("过期持有者的释放不影响新持有者")
    void staleReleaseDoesNotDropCurrentHolder() {
        MutableClock clock = new MutableClock(1_000_000L);
        CoordLockProvider first = provider("holder-1", clock);
        CoordLockProvider second = provider("holder-2", clock);
        LockLease stale = first.tryAcquire("order:1", Duration.ofMillis(150)).orElseThrow();

        clock.advance(Duration.ofMillis(200));
        LockLease current = second.tryAcquire("order:1", Duration.ofMillis(5_000)).orElseThrow();

        stale.close();

        assertThat(second.tryAcquire("order:1", Duration.ofMillis(5_000))).isEmpty();
        current.close();
        assertThat(second.tryAcquire("order:1", Duration.ofMillis(5_000))).isPresent();
    }

    @Test
    @DisplayName("损坏的记录显式失败，不静默让出锁")
    void corruptRecordFailsLoudly() {
        backend.seed("seedj:test:coord:lock:order:1", "{not-json");

        assertThatThrownBy(
                        () ->
                                provider("holder-1", Clock.systemUTC())
                                        .tryAcquire("order:1", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("agent 不可达映射为 PROVIDER_UNAVAILABLE 并点名地址")
    void unreachableAgentIsNamed() {
        backend.down(true);

        assertThatThrownBy(
                        () ->
                                provider("holder-1", Clock.systemUTC())
                                        .tryAcquire("order:1", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(LockErrorCode.PROVIDER_UNAVAILABLE);
                            assertThat(e.getMessage()).contains(ENDPOINT.toString());
                        });
    }

    @Test
    @DisplayName("close 后获取失败、重复 close 幂等")
    void closeIsFinal() {
        CoordLockProvider provider = provider("holder-1", Clock.systemUTC());
        provider.close();
        provider.close();

        assertThatThrownBy(() -> provider.tryAcquire("order:1", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.PROVIDER_CLOSED));
    }

    @Test
    @DisplayName("键过长显式失败")
    void rejectsOverlongKey() {
        String key = "k".repeat(256);

        assertThatThrownBy(
                        () ->
                                provider("holder-1", Clock.systemUTC())
                                        .tryAcquire(key, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        LockException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LockErrorCode.INVALID_KEY));
    }

    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
