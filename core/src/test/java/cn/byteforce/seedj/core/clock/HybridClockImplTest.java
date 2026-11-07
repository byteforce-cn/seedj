package cn.byteforce.seedj.core.clock;

import cn.byteforce.seedj.core.clock.spi.HybridStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 混合逻辑时钟：单实例严格递增、回拨不倒退、跨节点合并与状态持久化。
 */
class HybridClockImplTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    private static final String NODE = "node-a";

    private static long micros(Instant instant) {
        return HybridTimestamp.toMicros(instant);
    }

    private static InMemoryStateStore restoredAt(long physicalMicros) {
        InMemoryStateStore store = new InMemoryStateStore();
        store.states.put(NODE, new HybridTimestamp(physicalMicros, 0));
        return store;
    }

    @Test
    @DisplayName("无持久状态时首次取时等于物理时钟，且微秒精度")
    void firstReadUsesPhysicalClock() {
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), new InMemoryStateStore(), null);

        Instant now = clock.now();

        assertThat(now).isEqualTo(T0);
        assertThat(now.getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("物理时钟不前进时时间戳仍严格递增（微秒步进）")
    void strictlyIncreasingWhenPhysicalStalls() {
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), new InMemoryStateStore(), null);

        HybridTimestamp first = clock.nowTimestamp();
        HybridTimestamp second = clock.nowTimestamp();
        HybridTimestamp third = clock.nowTimestamp();

        assertThat(second.physicalMicros()).isEqualTo(first.physicalMicros() + 1);
        assertThat(second.logical()).isZero();
        assertThat(third.physicalMicros()).isEqualTo(first.physicalMicros() + 2);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    @DisplayName("物理时钟回拨后不产生倒退时间戳")
    void rollbackDoesNotGoBackwards() {
        TestClock physical = new TestClock(T0);
        HybridClock clock = Clocks.hybrid(NODE, physical, new InMemoryStateStore(), null);
        Instant first = clock.now();

        physical.set(T0.minus(Duration.ofHours(1)));
        Instant second = clock.now();

        assertThat(second).isAfter(first);
        assertThat(second).isEqualTo(first.plusNanos(1000));
    }

    @Test
    @DisplayName("构造期恢复的持久状态不会被回退覆盖")
    void restoredStateIsHonoured() {
        long persisted = micros(T0) + 60_000_000L;
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0),
                restoredAt(persisted), null);

        assertThat(clock.nowTimestamp().physicalMicros()).isEqualTo(persisted + 1);
    }

    @Test
    @DisplayName("远端物理值领先时本地推进到远端值并持久化")
    void mergeAdvancesToRemotePhysical() {
        long restored = micros(T0);
        InMemoryStateStore store = restoredAt(restored);
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), store, null);
        long remote = restored + 2_000_000L;

        clock.merge(new HybridTimestamp(remote, 3));

        assertThat(store.saved).containsExactly(new HybridTimestamp(remote, 4));
        assertThat(clock.nowTimestamp().physicalMicros()).isEqualTo(remote);
    }

    @Test
    @DisplayName("本地物理值领先时合并不回退，也不越过持久化阈值")
    void mergeKeepsLocalPhysical() {
        long restored = micros(T0);
        InMemoryStateStore store = restoredAt(restored);
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), store, null);
        long remote = restored + 2_000_000L;
        clock.merge(new HybridTimestamp(remote, 3));
        int savedBefore = store.saved.size();

        clock.merge(new HybridTimestamp(remote, 9));

        assertThat(store.saved).hasSize(savedBefore);
        assertThat(clock.nowTimestamp().physicalMicros()).isEqualTo(remote);
    }

    @Test
    @DisplayName("物理时钟领先双方的远端时间戳被吸收，逻辑计数归零")
    void mergeBehindPhysicalUsesPhysical() {
        InMemoryStateStore store = restoredAt(micros(T0) - 10_000_000L);
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), store, null);

        clock.merge(new HybridTimestamp(micros(T0) - 5_000_000L, 8));

        assertThat(store.saved).containsExactly(new HybridTimestamp(micros(T0), 0));
    }

    @Test
    @DisplayName("null 远端时间戳被忽略")
    void mergeIgnoresNull() {
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(T0), new InMemoryStateStore(), null);

        clock.merge(null);

        assertThat(clock.now()).isEqualTo(T0);
    }

    @Test
    @DisplayName("超过 1 秒阈值才持久化，阈值内的推进不再写入")
    void persistsOnlyAfterThreshold() {
        InMemoryStateStore store = restoredAt(micros(T0) - 10_000_000L);
        TestClock physical = new TestClock(T0);
        HybridClock clock = Clocks.hybrid(NODE, physical, store, null);

        clock.nowTimestamp();
        assertThat(store.saved).containsExactly(new HybridTimestamp(micros(T0), 0));

        physical.advance(Duration.ofSeconds(2));
        clock.nowTimestamp();
        assertThat(store.saved).containsExactly(
                new HybridTimestamp(micros(T0), 0),
                new HybridTimestamp(micros(T0) + 2_000_000L, 0));

        clock.nowTimestamp();
        assertThat(store.saved).hasSize(2);
    }

    @Test
    @DisplayName("持久化失败不阻塞取时")
    void persistenceFailureDoesNotBlock() {
        InMemoryStateStore store = restoredAt(micros(T0) - 10_000_000L);
        store.failOnSave = true;
        Instant physicalNow = T0.plusSeconds(30);
        HybridClock clock = Clocks.hybrid(NODE, new TestClock(physicalNow), store, null);

        assertThat(clock.now()).isEqualTo(physicalNow);
        assertThat(store.saved).isEmpty();
    }

    @Test
    @DisplayName("未恢复状态时永不持久化：阈值哨兵 Long.MIN_VALUE 相减溢出（疑似缺陷）")
    void freshClockNeverPersists() {
        InMemoryStateStore store = new InMemoryStateStore();
        TestClock physical = new TestClock(T0);
        HybridClock clock = Clocks.hybrid(NODE, physical, store, null);

        clock.nowTimestamp();
        physical.advance(Duration.ofHours(1));
        clock.nowTimestamp();

        assertThat(store.saved).isEmpty();
    }

    @Test
    @DisplayName("节点标识不可为空")
    void requiresNodeId() {
        assertThatThrownBy(() -> Clocks.hybrid(null, new TestClock(T0),
                new InMemoryStateStore(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nodeId");
    }

    private static final class InMemoryStateStore implements HybridStateStore {

        private final Map<String, HybridTimestamp> states = new HashMap<>();

        private final List<HybridTimestamp> saved = new ArrayList<>();

        private boolean failOnSave;

        @Override
        public HybridTimestamp load(String nodeId) {
            return states.get(nodeId);
        }

        @Override
        public void save(String nodeId, HybridTimestamp state) {
            if (failOnSave) {
                throw new IllegalStateException("store unavailable");
            }
            states.put(nodeId, state);
            saved.add(state);
        }
    }
}
