package cn.byteforce.seedj.demo.hr.query;

import cn.byteforce.seedj.cqrs.AbstractProjection;
import cn.byteforce.seedj.demo.hr.domain.EmployeeOnboarded;

/**
 * 把入职事件投影到员工目录。
 *
 * <p>订阅 topic 由 {@link EmployeeOnboarded} 上的 {@code @EventType} 推导，
 * 不需要手写字符串；消费者组名取自 {@link #projectionName()}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EmployeeDirectoryProjection extends AbstractProjection<EmployeeOnboarded> {

    private final EmployeeDirectory directory;

    public EmployeeDirectoryProjection(EmployeeDirectory directory) {
        super(EmployeeOnboarded.class);
        this.directory = directory;
    }

    @Override
    protected String projectionName() {
        return "demo.hr.employee.directory";
    }

    @Override
    protected void apply(EmployeeOnboarded event) {
        directory.upsert(new EmployeeView(event.employeeId(), event.employeeNo(), event.name(),
                event.department(), event.position(), event.hireDate()));
    }
}
