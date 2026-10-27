package cn.byteforce.seedj.starter.eis.domain;

import java.util.List;

/**
 * 事件日志的读取与原子追加。
 *
 * <p>本接口是 SPI：库内提供内存实现（开发/测试）与 PostgreSQL 实现（生产）。
 * 实现必须保证 {@link #append} 的「版本 CAS + 原子写入」——并发写同一流时，
 * 只有一个能成功，另一个抛 {@link OptimisticLockingException}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventLogStore {

    /**
     * 加载流的全部事件，并带上流头版本。
     *
     * @param streamId 流标识
     * @return 事件流视图（不存在时为空流，版本 0）
     */
    EventStream loadStream(String streamId);

    /**
     * 只加载版本号大于 {@code fromVersion} 的事件，供快照装载使用。
     *
     * @param streamId    流标识
     * @param fromVersion 已覆盖到的版本（不含）
     * @return 事件流视图
     */
    EventStream loadStream(String streamId, long fromVersion);

    /**
     * 原子追加事件。
     *
     * @param streamId        流标识
     * @param aggregateType   聚合类型名（排障用）
     * @param expectedVersion 乐观锁基准版本，0 表示新建
     * @param events          待追加事件，不允许为空列表
     * @throws OptimisticLockingException 版本冲突
     */
    void append(String streamId, String aggregateType, long expectedVersion, List<DomainEvent> events);
}
