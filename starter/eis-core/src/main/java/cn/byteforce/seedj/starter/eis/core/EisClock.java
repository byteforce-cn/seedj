package cn.byteforce.seedj.starter.eis.core;

import java.time.Instant;

/**
 * EIS 时钟：全链路 UTC 时间语义的唯一时间来源。
 *
 * <p>对应 EIS 时间契约（{@code 03-time-contract/temporal.rules.yaml} T-R007）：
 * 所有时间字段为 UTC、微秒精度；参考内核（{@code eis/tools/lib/kernel.py}）在场景执行
 * 中通过可推进时钟产生确定性时序，本接口是其 Java 对齐面——业务代码一律经
 * {@link #now()} 取时，测试可注入固定/受控时钟。</p>
 *
 * <p><b>单调性</b>：运行时不强依赖时钟严格递增，但双时间轴封口约束
 * （{@code superseded_at > recorded_at}）要求同一对象的连续版本时间严格递进；
 * 生产默认使用 {@link #monotonic(EisClock)} 包装系统时钟，在时钟回拨或同微秒
 * 连续取时场景下以 +1 微秒推进，保证约束恒成立。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface EisClock {

    /**
     * 当前 UTC 时刻。
     *
     * @return 当前时刻（不晚于真实时间 1 微秒以上的偏差仅可能来自单调包装）
     */
    Instant now();

    /**
     * 系统时钟（UTC，微秒可用性取决于宿主）。
     *
     * @return 系统时钟
     */
    static EisClock system() {
        return Instant::now;
    }

    /**
     * 固定时钟（测试专用）。
     *
     * @param instant 固定时刻
     * @return 恒返回给定时刻的时钟
     */
    static EisClock fixed(Instant instant) {
        return () -> instant;
    }

    /**
     * 单调递增包装：保证相邻两次调用的返回值严格递增（最小步进 1 微秒）。
     *
     * <p>用于满足 PostgreSQL 侧 {@code recorded_range} 检查
     * （{@code superseded_at IS NULL OR superseded_at > recorded_at}）。</p>
     *
     * @param delegate 底层时钟
     * @return 单调化时钟
     */
    static EisClock monotonic(EisClock delegate) {
        return new MonotonicClock(delegate);
    }

    /**
     * 单调时钟实现：线程安全（同步取时，代价可忽略）。
     */
    final class MonotonicClock implements EisClock {

        private final EisClock delegate;

        private Instant last;

        private MonotonicClock(EisClock delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized Instant now() {
            Instant current = delegate.now();
            if (last != null && !current.isAfter(last)) {
                current = last.plusNanos(1_000L);
            }
            last = current;
            return current;
        }
    }
}
