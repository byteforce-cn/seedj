package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.lock.LockClient;
import cn.byteforce.coord.sdk.lock.LockInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁服务（C3）：专用 API —— 获取 / 续约 / 释放 / 失锁回调 +
 * 看门狗续约；**不实现** {@code java.util.concurrent.locks.Lock}、不模拟可重入
 * （seedj ADR-0001 §决策 6）。
 *
 * <p>等待语义：{@code waitTimeout} 内轮询尝试（100ms 间隔），超时返回空 / 抛
 * {@link CoordLockTimeoutException}；调用失败（Agent 不可达等）按 C5 直接抛
 * {@link cn.byteforce.coord.sdk.CoordException}，不吞错、不模拟本地降级。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordLockService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoordLockService.class);

    /** 等待轮询间隔。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** 看门狗周期下限（C3）。 */
    private static final Duration MIN_WATCHDOG_PERIOD = Duration.ofSeconds(1);

    private final LockClient lockClient;
    private final String holderId;
    private final Duration defaultTtl;
    private final Duration watchdogPeriod;
    private final List<CoordLockLostListener> listeners;
    private final ScheduledExecutorService scheduler;

    /**
     * 生产构造。
     *
     * @param lockClient    SDK 锁客户端
     * @param holderId      持有者 ID（C3：{@code {app}:{host}:{pid}} 口径）
     * @param defaultTtl    默认租约 TTL
     * @param listeners     失锁回调（可为空列表）
     */
    public CoordLockService(LockClient lockClient, String holderId, Duration defaultTtl,
                            List<CoordLockLostListener> listeners) {
        this(lockClient, holderId, defaultTtl, listeners, watchdogPeriodFor(defaultTtl));
    }

    /**
     * 测试用构造（可调看门狗周期）。
     *
     * @param lockClient     SDK 锁客户端
     * @param holderId       持有者 ID
     * @param defaultTtl     默认租约 TTL
     * @param listeners      失锁回调
     * @param watchdogPeriod 看门狗周期
     */
    CoordLockService(LockClient lockClient, String holderId, Duration defaultTtl,
                     List<CoordLockLostListener> listeners, Duration watchdogPeriod) {
        this.lockClient = lockClient;
        this.holderId = holderId;
        this.defaultTtl = defaultTtl;
        this.listeners = List.copyOf(listeners);
        this.watchdogPeriod = watchdogPeriod;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coord-lock-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 当前持有者 ID。
     *
     * @return holder_id
     */
    public String getHolderId() {
        return holderId;
    }

    /**
     * 立即尝试获取（不等待）。
     *
     * @param lockName 锁名
     * @return 成功时返回租约（已启用看门狗）；被占用返回空
     */
    public Optional<CoordLockLease> acquire(String lockName) {
        return acquire(lockName, Duration.ZERO, defaultTtl);
    }

    /**
     * 在等待窗口内轮询获取。
     *
     * @param lockName    锁名
     * @param waitTimeout 等待窗口（0 = 仅尝试一次）
     * @return 成功时返回租约；超时返回空
     */
    public Optional<CoordLockLease> acquire(String lockName, Duration waitTimeout) {
        return acquire(lockName, waitTimeout, defaultTtl);
    }

    /**
     * 在等待窗口内轮询获取（自定义 TTL）。
     *
     * @param lockName    锁名
     * @param waitTimeout 等待窗口
     * @param ttl         租约 TTL
     * @return 成功时返回租约；超时返回空
     */
    public Optional<CoordLockLease> acquire(String lockName, Duration waitTimeout, Duration ttl) {
        long deadline = System.nanoTime() + Math.max(0L, waitTimeout.toNanos());
        while (true) {
            LockInfo info = lockClient.acquire(lockName, holderId, ttlSeconds(ttl));
            if (info != null && info.isAcquired()) {
                return Optional.of(new CoordLockLease(lockClient, lockName, holderId, info.getLeaseId(),
                        ttl, watchdogPeriod, listeners, scheduler));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Optional.empty();
            }
            sleepQuietly(Math.min(POLL_INTERVAL.toNanos(), remaining), lockName, waitTimeout);
        }
    }

    /**
     * 获取锁或将超时转为异常。
     *
     * @param lockName    锁名
     * @param waitTimeout 等待窗口
     * @return 租约
     * @throws CoordLockTimeoutException 等待超时 / 等待被中断
     */
    public CoordLockLease acquireOrThrow(String lockName, Duration waitTimeout) {
        return acquire(lockName, waitTimeout)
                .orElseThrow(() -> new CoordLockTimeoutException(
                        "获取锁超时: lock=" + lockName + ", waitTimeout=" + waitTimeout + ", holder=" + holderId));
    }

    /**
     * 获取锁或将超时转为异常（自定义 TTL）。
     *
     * @param lockName    锁名
     * @param waitTimeout 等待窗口
     * @param ttl         租约 TTL
     * @return 租约
     * @throws CoordLockTimeoutException 等待超时 / 等待被中断
     */
    public CoordLockLease acquireOrThrow(String lockName, Duration waitTimeout, Duration ttl) {
        return acquire(lockName, waitTimeout, ttl)
                .orElseThrow(() -> new CoordLockTimeoutException(
                        "获取锁超时: lock=" + lockName + ", waitTimeout=" + waitTimeout + ", holder=" + holderId));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void sleepQuietly(long nanos, String lockName, Duration waitTimeout) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CoordLockTimeoutException(
                    "等待获取锁被中断: lock=" + lockName + ", waitTimeout=" + waitTimeout + ", holder=" + holderId, e);
        }
    }

    private static int ttlSeconds(Duration ttl) {
        return (int) Math.max(1L, Math.min(ttl.toSeconds(), Integer.MAX_VALUE));
    }

    private static Duration watchdogPeriodFor(Duration ttl) {
        Duration period = ttl.dividedBy(3);
        return period.compareTo(MIN_WATCHDOG_PERIOD) < 0 ? MIN_WATCHDOG_PERIOD : period;
    }
}
