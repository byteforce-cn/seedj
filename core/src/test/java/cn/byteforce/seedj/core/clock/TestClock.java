package cn.byteforce.seedj.core.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 可手动推进的测试时钟（test-jar 共享）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TestClock implements Clock {

    private Instant now;

    /**
     * 构造测试时钟。
     *
     * @param start 初始时刻
     */
    public TestClock(Instant start) {
        set(start);
    }

    /**
     * 设置当前时刻（截断到微秒）。
     *
     * @param instant 新时刻
     */
    public void set(Instant instant) {
        this.now = Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 前进指定时长。
     *
     * @param duration 时长
     */
    public void advance(Duration duration) {
        set(now.plus(Objects.requireNonNull(duration, "duration")));
    }

    @Override
    public Instant now() {
        return now;
    }
}
