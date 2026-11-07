package cn.byteforce.seedj.core.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时钟一致性测试套件自身：通过路径与失败信息。
 */
class ClockTckTest {

    private static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    @Test
    @DisplayName("恒定时钟通过基础校验，但被严格递增校验拒绝")
    void basicPassesButIncreasingFails() {
        Clock stalled = Clocks.fixed(T0);

        assertThatCode(() -> ClockTck.verifyBasic(stalled)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ClockTck.verifyStrictlyIncreasing(stalled, 3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    @DisplayName("非微秒精度的时钟在基础校验中失败")
    void rejectsSubMicrosecondPrecision() {
        Clock nanos = () -> T0.plusNanos(1);

        assertThatThrownBy(() -> ClockTck.verifyBasic(nanos))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("microsecond precision");
    }

    @Test
    @DisplayName("返回 null 的时钟在基础校验中失败")
    void rejectsNullNow() {
        Clock nullable = () -> null;

        assertThatThrownBy(() -> ClockTck.verifyBasic(nullable))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not return null");
    }

    @Test
    @DisplayName("单调时钟通过严格递增校验")
    void monotonicClockPassesIncreasing() {
        Clock clock = Clocks.monotonic(Clocks.fixed(T0));

        assertThatCode(() -> ClockTck.verifyStrictlyIncreasing(clock, 16))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("回拨后仍前进的时钟通过回拨校验")
    void rollbackIsTolerated() {
        TestClock physical = new TestClock(T0);
        Clock clock = Clocks.monotonic(physical);

        assertThatCode(() -> ClockTck.verifyClockRollback(clock,
                first -> physical.set(first.minusSeconds(3600))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("回拨就倒退的时钟在回拨校验中失败")
    void plainFixedClockFailsRollback() {
        TestClock physical = new TestClock(T0);

        assertThatThrownBy(() -> ClockTck.verifyClockRollback(physical,
                first -> physical.set(first.minusSeconds(3600))))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not go backwards");
    }

    @Test
    @DisplayName("并发取时结果无重复且严格递增")
    void concurrentCallsStayUnique() throws InterruptedException {
        Clock clock = Clocks.monotonic(Clocks.fixed(T0));

        assertThatCode(() -> ClockTck.verifyConcurrent(clock, 4, 25))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("并发取时出现重复值时校验失败")
    void concurrentDuplicatesFail() {
        Clock stalled = Clocks.fixed(T0);

        assertThatThrownBy(() -> ClockTck.verifyConcurrent(stalled, 2, 4))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate");
    }
}
