package cn.byteforce.seedj.lock;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 分布式锁能力入口，同时是 provider 实现契约。
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>{@link #tryAcquire} <b>不阻塞</b>：立即返回"拿到"或"没拿到"，把等待策略留给调用方；
 *   <li>租约到期后必须可被其他调用方获取——这是崩溃恢复的唯一机制；
 *   <li>{@link LockLease#close()} 幂等，且只能释放自己持有的租约；
 *   <li>provider 不可用（网络、连接池耗尽）时抛 {@link LockException}（{@code PROVIDER_UNAVAILABLE}，可重试），
 *       <b>不得</b>假装获取成功，也不得假装失败——静默降级会让临界区失效。
 * </ul>
 *
 * <h2>它不保证什么</h2>
 *
 * <ul>
 *   <li><b>不保证互斥的绝对性</b>：租约过期、时钟漂移、GC 停顿都可能让两个持有者重叠。 需要强一致的场景，用存储侧唯一约束 / 条件更新 / 乐观锁兜底；
 *   <li><b>不提供公平性</b>；
 *   <li><b>不提供可重入</b>：同一线程重复获取同一个键会失败或覆盖租约，取决于实现， 调用方不要依赖。
 * </ul>
 *
 * <p>能力是<b>独立</b>的：CQRS 命令模板、outbox relay、workflow 调度、业务对账都注入 本接口，没有任何一个能力拥有它。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface LockProvider extends AutoCloseable {

    /**
     * 尝试获取锁，不阻塞。
     *
     * @param key 锁键，不得为空白
     * @param ttl 租约时长，必须为正
     * @return 获取成功返回租约；已被占用返回空
     * @throws LockException 键/租约非法，或 provider 不可用
     */
    Optional<LockLease> tryAcquire(String key, Duration ttl);

    /**
     * 获取锁，失败即抛异常。
     *
     * @throws LockException {@code LOCK_BUSY}（已被占用，可重试）
     */
    default LockLease acquire(String key, Duration ttl) {
        Objects.requireNonNull(key, "key");
        return tryAcquire(key, ttl)
                .orElseThrow(
                        () -> new LockException(LockErrorCode.LOCK_BUSY, "lock is busy: " + key));
    }

    /** 释放 provider 内部资源。幂等且不抛。 */
    @Override
    default void close() {}

    /**
     * 不加锁的 provider：总是获取成功、释放为空操作。
     *
     * <p>只用于单进程 / 测试 / 明确不需要互斥的场景。它会让"临界区"完全敞开， 所以不要把它当成默认值塞进生产装配。
     */
    static LockProvider disabled() {
        return new LockProvider() {
            @Override
            public Optional<LockLease> tryAcquire(String key, Duration ttl) {
                return Optional.of(LockLease.of(key, () -> {}));
            }
        };
    }
}
