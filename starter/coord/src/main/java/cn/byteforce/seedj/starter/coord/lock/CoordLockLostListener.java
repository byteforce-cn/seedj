package cn.byteforce.seedj.starter.coord.lock;

/**
 * 失锁回调：续约失败或锁已易主（外部释放 / 租约过期被抢占）时触发。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CoordLockLostListener {

    /**
     * 锁已丢失（回调线程为看门狗调度线程；实现应尽快返回）。
     *
     * @param lease 已丢失的租约（{@code lease.isValid()} 为 false）
     */
    void onLockLost(CoordLockLease lease);
}
