package cn.byteforce.seedj.core.clock;

import java.time.Instant;

/**
 * 统一时钟：全链路 UTC 时间语义的唯一取时入口。
 *
 * <p>业务代码一律经 {@link #now()} 取时，不得直接调用 {@link Instant#now()}
 * 或其他时间 API。所有实现必须返回微秒精度时刻（nano 为 1000 的整数倍）。</p>
 *
 * <p>接口本身不承诺相邻调用严格递增。需要严格递进的场景应注入
 * {@link Clocks#monotonic(Clock)} 或 {@link HybridClock}。
 * 跨节点能力由实现的能力声明（{@link ClockGuarantee}）给出，业务只能依赖
 * 声明级别的保证。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface Clock {

    /**
     * 当前 UTC 时刻（微秒精度）。
     *
     * @return 当前时刻，nano 部分为 1000 的整数倍
     */
    Instant now();
}
