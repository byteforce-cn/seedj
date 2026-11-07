package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.EventTypeRegistrar;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.domain.event.DomainEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 事件溯源仓储：把 {@link Repository} 的两件事（按标识加载、保存）实现为 "读流 + 重放" 与 "按期望版本追加"。
 *
 * <p>业务方只需要三件事：
 *
 * <pre>{@code
 * public final class EmployeeRepository extends EventSourcedRepository<Employee, EmployeeId> {
 *     public EmployeeRepository(EventStore<EmployeeId> store) { super(store); }
 *     protected Class<Employee> aggregateType() { return Employee.class; }
 *     protected Employee empty(EmployeeId id) { return Employee.blank(id); }
 *     protected String streamType() { return "hr.employee"; }   // 稳定逻辑名
 * }
 * }</pre>
 *
 * <p><b>发布事件是调用方的事</b>：本类只负责让聚合状态与事件流一致。需要对外投递时， 在同一事务内用 {@code EventPublisher#publish}，由 outbox
 * 保证与事件流同生共死； 仓储不内置发布，避免"事件已投递但流写入回滚"的半个事务。
 *
 * @param <T> 聚合类型
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class EventSourcedRepository<T extends AbstractAggregateRoot<ID>, ID>
        implements Repository<T, ID> {

    private final EventStore<ID> store;

    protected EventSourcedRepository(EventStore<ID> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** 聚合类型。用于诊断与默认流类型名。 */
    protected abstract Class<T> aggregateType();

    /**
     * 供重放使用的空实例：只允许设置标识，不得产生事件或副作用。
     *
     * <p>通常在聚合上提供一个包内可见的 {@code static blank(ID)} 工厂， 把"重放构造"与"业务构造"分开。
     */
    protected abstract T empty(ID id);

    /** 流类型名。默认取 {@link #aggregateType()} 的简单类名； 类可能改名/移动包时请覆写为稳定逻辑名（如 {@code hr.employee}）。 */
    protected String streamType() {
        return aggregateType().getSimpleName();
    }

    /** 流标识。 */
    protected StreamId<ID> streamId(ID id) {
        return new StreamId<>(streamType(), id);
    }

    @Override
    public Optional<T> load(ID id) {
        Objects.requireNonNull(id, "id");
        registerEventTypes();
        EventStream<ID> stream = store.read(streamId(id));
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        T aggregate = empty(id);
        aggregate.replayAll(stream.events(), stream.version());
        return Optional.of(aggregate);
    }

    /**
     * 让需要名称解析的存储（如 JDBC）知道本聚合会处理哪些事件。
     *
     * <p>注册是幂等的；每次加载都调用，避免应用重启后第一次读取因注册表为空而 把历史事件误判为损坏。
     */
    private void registerEventTypes() {
        if (store instanceof EventTypeRegistrar registrar) {
            registrar.register(aggregateType());
        }
    }

    @Override
    public void save(T aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        List<DomainEvent> events = aggregate.peekDomainEvents();
        if (events.isEmpty()) {
            return;
        }
        long expectedVersion = aggregate.expectedVersion();
        store.append(streamId(aggregate.getId()), expectedVersion, events);
        aggregate.clearDomainEvents();
    }
}
