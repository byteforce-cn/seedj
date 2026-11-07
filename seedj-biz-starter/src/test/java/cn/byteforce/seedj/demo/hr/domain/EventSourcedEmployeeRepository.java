package cn.byteforce.seedj.demo.hr.domain;

import cn.byteforce.seedj.cqrs.EventSourcedRepository;
import cn.byteforce.seedj.cqrs.spi.EventStore;

/**
 * 员工仓储的事件溯源实现：加载 = 读流 + 重放，保存 = 按期望版本追加。
 *
 * <p>{@code streamType()} 返回稳定逻辑名而不是类名：类改名/移动包后，历史事件流 仍然能被找到。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EventSourcedEmployeeRepository
        extends EventSourcedRepository<Employee, EmployeeId> implements EmployeeRepository {

    public EventSourcedEmployeeRepository(EventStore<EmployeeId> store) {
        super(store);
    }

    @Override
    protected Class<Employee> aggregateType() {
        return Employee.class;
    }

    @Override
    protected Employee empty(EmployeeId id) {
        return Employee.blank(id);
    }

    @Override
    protected String streamType() {
        return Employee.STREAM_TYPE;
    }
}
