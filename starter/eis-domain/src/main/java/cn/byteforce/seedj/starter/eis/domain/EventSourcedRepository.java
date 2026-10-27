package cn.byteforce.seedj.starter.eis.domain;

import java.util.List;
import java.util.Optional;

/**
 * 基于事件日志的聚合仓储默认实现。
 *
 * <p>装载：读流 → 交给 {@link AggregateFactory} 重放。<br>
 * 保存：取待提交事件 → {@link EventLogStore#append}（失败则事件保留，可重试）→ 清空。</p>
 *
 * <p>并发冲突不会被吞掉：{@link EventLogStore#append} 抛出的
 * {@link OptimisticLockingException} 会原样传给调用方，且此时聚合仍持有未提交事件。</p>
 *
 * @param <T>  聚合类型
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public class EventSourcedRepository<T extends AggregateRoot<ID>, ID> implements AggregateRepository<T, ID> {

    private final EventLogStore eventLogStore;

    private final AggregateFactory<T, ID> factory;

    /**
     * 构造仓储。
     *
     * @param eventLogStore 事件日志
     * @param factory       聚合重建工厂（通常传 {@code Xxx::rehydrate}）
     */
    public EventSourcedRepository(EventLogStore eventLogStore, AggregateFactory<T, ID> factory) {
        this.eventLogStore = eventLogStore;
        this.factory = factory;
    }

    /**
     * 按标识加载聚合：读流并重放历史。
     *
     * @param id 聚合标识
     * @return 聚合；流不存在时为空
     */
    @Override
    public Optional<T> load(ID id) {
        String streamId = String.valueOf(id);
        EventStream stream = eventLogStore.loadStream(streamId);
        if (stream.currentVersion() == 0 && stream.events().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(factory.rehydrate(id, stream.currentVersion(), stream.events()));
    }

    /**
     * 保存聚合：追加事件并清空待提交列表。
     *
     * @param aggregate 聚合
     * @throws OptimisticLockingException 版本冲突
     */
    @Override
    public void save(T aggregate) {
        List<DomainEvent> events = aggregate.peekDomainEvents();
        if (events.isEmpty()) {
            return;
        }
        long expectedVersion = aggregate.expectedVersion();
        String streamId = String.valueOf(aggregate.getId());
        String aggregateType = aggregate.getClass().getSimpleName();

        eventLogStore.append(streamId, aggregateType, expectedVersion, events);
        aggregate.clearDomainEvents();
    }
}
