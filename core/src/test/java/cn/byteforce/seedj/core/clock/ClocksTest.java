package cn.byteforce.seedj.core.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时钟工厂：系统/固定/单调包装的精度与递进语义。
 */
class ClocksTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00.123456789Z");

    @Test
    @DisplayName("system() 返回微秒精度时刻")
    void systemIsMicrosecondPrecision() {
        assertThat(Clocks.system().now().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("fixed() 截断到微秒并恒定返回同一时刻")
    void fixedIsStableAndTruncated() {
        Clock clock = Clocks.fixed(T0);

        assertThat(clock.now()).isEqualTo(Instant.parse("2026-11-06T09:00:00.123456Z"));
        assertThat(clock.now()).isEqualTo(clock.now());
    }

    @Test
    @DisplayName("fixed() 拒绝空时刻")
    void fixedRequiresInstant() {
        assertThatThrownBy(() -> Clocks.fixed(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("monotonic() 对停滞的委托时钟按 1 微秒步进")
    void monotonicAdvancesStalledDelegate() {
        Clock clock = Clocks.monotonic(Clocks.fixed(T0));

        Instant first = clock.now();
        Instant second = clock.now();
        Instant third = clock.now();

        assertThat(second).isEqualTo(first.plusNanos(1000));
        assertThat(third).isEqualTo(first.plusNanos(2000));
    }

    @Test
    @DisplayName("monotonic() 在委托时钟前进时直接采用委托值")
    void monotonicFollowsAdvancingDelegate() {
        TestClock delegate = new TestClock(T0);
        Clock clock = Clocks.monotonic(delegate);

        Instant first = clock.now();
        delegate.advance(java.time.Duration.ofSeconds(5));
        Instant second = clock.now();

        assertThat(second).isEqualTo(first.plusSeconds(5));
    }

    @Test
    @DisplayName("monotonic() 拒绝空委托与空返回值")
    void monotonicRejectsNulls() {
        assertThatThrownBy(() -> Clocks.monotonic(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate");

        Clock nullReturning = Clocks.monotonic(() -> null);
        assertThatThrownBy(nullReturning::now)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("delegate.now()");
    }

    @Test
    @DisplayName("HybridTimestamp 按物理值再按逻辑值排序")
    void hybridTimestampOrdering() {
        HybridTimestamp base = HybridTimestamp.of(Instant.parse("2026-11-06T09:00:00Z"), 1);

        assertThat(base.compareTo(new HybridTimestamp(base.physicalMicros(), 2))).isNegative();
        assertThat(base.compareTo(new HybridTimestamp(base.physicalMicros() + 1, 0))).isNegative();
        assertThat(base.compareTo(base)).isZero();
        assertThat(base).isLessThan(new HybridTimestamp(base.physicalMicros(), 2));
    }

    @Test
    @DisplayName("HybridTimestamp 逻辑计数器与微秒换算有边界校验")
    void hybridTimestampGuards() {
        assertThatThrownBy(() -> new HybridTimestamp(0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical");

        HybridTimestamp stamp = new HybridTimestamp(1_500_000L, 0);
        assertThat(stamp.physicalInstant()).isEqualTo(Instant.parse("1970-01-01T00:00:01.500Z"));

        assertThatThrownBy(() -> HybridTimestamp.toMicros(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(HybridTimestamp.toMicros(T0)).isEqualTo(1_793_955_600_123_456L);
    }
}
