package cn.byteforce.seedj.core.clock;

import cn.byteforce.seedj.core.clock.spi.GlobalSequencer;
import cn.byteforce.seedj.core.clock.spi.HybridStateStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * {@link Clock} 的工厂与装配入口。业务代码不应直接使用本类。
 */
public final class Clocks {

    private Clocks() {
    }

    /** 系统时钟：微秒精度，不保证单调。 */
    public static Clock system() {
        return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 固定时钟（测试专用）：微秒精度，不保证严格递进。 */
    public static Clock fixed(Instant instant) {
        Instant fixed = Objects.requireNonNull(instant, "instant")
                .truncatedTo(ChronoUnit.MICROS);
        return () -> fixed;
    }

    /** 单调包装：同一实例相邻调用严格递增，最小步进 1 微秒。 */
    public static Clock monotonic(Clock delegate) {
        return new MonotonicClock(delegate);
    }

    /**
     * 混合逻辑时钟：单实例严格递增 + 跨节点因果一致。
     *
     * @param nodeId     节点标识，用于持久化 HLC 状态
     * @param physical   物理时钟（通常 {@link #system()}）
     * @param stateStore 状态存取 SPI
     * @param sequencer  全局序列锚点，可为 {@code null}
     */
    public static HybridClock hybrid(String nodeId,
                                     Clock physical,
                                     HybridStateStore stateStore,
                                     GlobalSequencer sequencer) {
        return new HybridClockImpl(nodeId, physical, stateStore, sequencer);
    }

    private static final class MonotonicClock implements Clock {

        private final Clock delegate;
        private Instant last;

        private MonotonicClock(Clock delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized Instant now() {
            Instant current = Objects.requireNonNull(delegate.now(), "delegate.now()")
                    .truncatedTo(ChronoUnit.MICROS);
            if (last != null && !current.isAfter(last)) {
                current = last.plus(1, ChronoUnit.MICROS);
            }
            last = current;
            return current;
        }
    }
}
