package cn.byteforce.seedj.demo.hr.domain;

/**
 * 员工标识：业务值对象，而不是裸 {@code String}。
 *
 * <p>把它做成独立类型，是为了让"员工标识"与"事件标识""部门标识"在编译期就不可互换。
 * 框架侧完全不需要知道它长什么样：{@code AbstractAggregateRoot<EmployeeId>}、
 * {@code Repository<Employee, EmployeeId>}、{@code EventStore<EmployeeId>} 都靠类型参数传递。
 *
 * @param value 稳定不可变的业务编码
 * @author ByteForce
 * @since 1.0.0
 */
public record EmployeeId(String value) {

    public EmployeeId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("employeeId must not be blank");
        }
    }

    public static EmployeeId of(String value) {
        return new EmployeeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
