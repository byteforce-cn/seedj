package cn.byteforce.seedj.starter.coord.lock;

import cn.byteforce.coord.sdk.lock.LockClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 锁租约（C3）：{@code (holder_id, lease_id)} 持有者凭据 + 看门狗续约 + 失锁回调。
 *
 * <p>语义边界（seedj ADR-0001 §决策 6）：</p>
 * <ul>
 *   <li>**不实现** {@code java.util.concurrent.locks.Lock}、不模拟可重入；</li>
 *   <li>看门狗按 {@code ttl/3}（下限 1s）自动续约；续约失败（含锁被外部释放 /
 *       过期抢占）→ 标记失锁 + 通知 {@link CoordLockLostListener}，且不再发送任何
 *       RPC（锁已可能易主，释放会对新持有者造成破坏）；</li>
 *   <li>持有者凭据 {@code (holderId, leaseId)} 对外暴露用于 fencing 讨论；
 *       其强度（leaseId 单调性）以契约为准（C3）。</li>
 * </ul>
 *
 * <p>临界区建议：进入临界区前检查 {@link #isValid()}，并在需要强 fencing 的场景
 * 将 {@code (holderId, leaseId)} 传递给下游资源侧做校验。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordLockLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoordLockLease.class);

    private final LockClient lockClient;
    private final String name;
    private final String holderId;
    private final long leaseId;
    private final Duration ttl;
    private final List<CoordLockLostListener> listeners;
    private final ScheduledFuture<?> watchdog;

    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicBoolean released = new AtomicBoolean();

    /**
     * 内部构造（由 {@link CoordLockService} 使用）。
     *
     * @param lockClient SDK 锁客户端
     * @param name       锁名
     * @param holderId   持有者 ID
     * @param leaseId    契约返回的租约 ID
     * @param ttl        租约 TTL
     * @param watchdogPeriod 看门狗周期
     * @param listeners  失锁回调
     * @param scheduler  看门狗调度器（由服务共享）
     */
    CoordLockLease(LockClient lockClient, String name, String holderId, long leaseId, Duration ttl,
                   Duration watchdogPeriod, List<CoordLockLostListener> listeners,
                   ScheduledExecutorService scheduler) {
        this.lockClient = lockClient;
        this.name = name;
        this.holderId = holderId;
        this.leaseId = leaseId;
        this.ttl = ttl;
        this.listeners = listeners;
        this.watchdog = scheduler.scheduleAtFixedRate(this::renewQuietly,
                watchdogPeriod.toMillis(), watchdogPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 锁名。
     */
    public String getName() {
        return name;
    }

    /**
     * 持有者 ID（{@code holder_id}）。
     */
    public String getHolderId() {
        return holderId;
    }

    /**
     * 租约 ID（{@code lease_id}，fencing 凭据之一）。
     */
    public long getLeaseId() {
        return leaseId;
    }

    /**
     * 租约 TTL。
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * 当前是否仍持有锁（未释放且未失锁）。
     *
     * @return true 表示可安全进入临界区
     */
    public boolean isValid() {
        return valid.get();
    }

    /**
     * 手动续约（看门狗之外的显式续约）。
     *
     * @return true 表示续约成功；false 表示已失锁 / 已释放（已触发/将触发回调）
     */
    public boolean renew() {
        if (released.get() || !valid.get()) {
            return false;
        }
        boolean renewed;
        try {
            renewed = lockClient.renew(name, holderId, leaseId);
        } catch (RuntimeException e) {
            log.warn("锁续约异常（按失锁处理）: lock={}, holder={}, lease={}，原因: {}",
                    name, holderId, leaseId, e.getMessage());
            markLost();
            return false;
        }
        if (!renewed) {
            log.warn("锁续约被拒（已过期或易主）: lock={}, holder={}, lease={}", name, holderId, leaseId);
            markLost();
        }
        return renewed;
    }

    /**
     * 释放锁（幂等；已失锁时不再发送 RPC——锁可能已易主）。
     */
    public void release() {
        if (released.compareAndSet(false, true)) {
            watchdog.cancel(false);
            if (!valid.get()) {
                // 已失锁：跳过远程释放，避免误删新持有者的租约
                return;
            }
            valid.set(false);
            try {
                boolean releasedRemote = lockClient.release(name, holderId, leaseId);
                if (!releasedRemote) {
                    log.warn("锁释放被拒（租约可能已过期或易主）: lock={}, holder={}, lease={}",
                            name, holderId, leaseId);
                }
            } catch (RuntimeException e) {
                log.warn("锁释放失败（依赖 TTL 过期兜底）: lock={}, holder={}, lease={}，原因: {}",
                        name, holderId, leaseId, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        release();
    }

    private void renewQuietly() {
        if (valid.get()) {
            renew();
        } else {
            watchdog.cancel(false);
        }
    }

    private void markLost() {
        if (valid.compareAndSet(true, false)) {
            watchdog.cancel(false);
            for (CoordLockLostListener listener : listeners) {
                try {
                    listener.onLockLost(this);
                } catch (RuntimeException e) {
                    log.warn("失锁回调执行失败（已忽略）: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public String toString() {
        return "CoordLockLease{" + name + ", holder=" + holderId + ", lease=" + leaseId
                + ", valid=" + valid.get() + '}';
    }
}
