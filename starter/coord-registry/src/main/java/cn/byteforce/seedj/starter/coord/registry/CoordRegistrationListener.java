package cn.byteforce.seedj.starter.coord.registry;

/**
 * 自动注册生命周期回调（扩展点：可注入多个实现，按 Bean 顺序依次通知）。
 *
 * <p>全部方法均有默认空实现，使用方按需覆写。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface CoordRegistrationListener {

    /**
     * 实例注册成功（每个生命周期内仅在首次成功时通知；心跳恢复重注册不重复通知）。
     *
     * @param instance 已注册实例视图
     */
    default void onRegistered(CoordServiceInstance instance) {
    }

    /**
     * 实例主动注销成功（容器关闭时的优雅注销）。
     *
     * @param instance 已注销实例视图
     */
    default void onDeregistered(CoordServiceInstance instance) {
    }

    /**
     * 一次注册尝试失败（库会退避重试，不阻塞启动）。
     *
     * @param serviceName 服务名
     * @param attempt     连续失败次数（从 1 起）
     * @param cause       失败原因
     */
    default void onRegisterFailed(String serviceName, int attempt, Throwable cause) {
    }
}
