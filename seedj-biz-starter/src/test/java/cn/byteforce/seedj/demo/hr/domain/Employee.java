package cn.byteforce.seedj.demo.hr.domain;

import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.event.EventApplier;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 员工聚合（事件溯源）。
 *
 * <p>状态只能通过 {@link #apply(EmployeeOnboarded)} 改变：命令方法不直接赋值，
 * 而是 {@code raise} 一个事件，由 apply 落到字段上。这样"内存状态"与"事件流重放"
 * 永远是同一条路径，不存在"命令改了状态但事件没记下来"的半截状态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class Employee extends AbstractAggregateRoot<EmployeeId>
        implements EventApplier<EmployeeOnboarded> {

    /**
     * 流类型名：仓储与命令代理共用同一个常量，避免两处字面量漂移。
     */
    public static final String STREAM_TYPE = "hr.employee";

    private String employeeNo;

    private String name;

    private String department;

    private String position;

    private LocalDate hireDate;

    private Employee(EmployeeId id) {
        super(id);
    }

    /**
     * 空实例，仅供仓储重放历史时使用。
     *
     * <p>不校验业务必填项：重放路径必须先有历史，历史本身由 {@link #onboard} 保证合法。
     */
    public static Employee blank(EmployeeId id) {
        return new Employee(Objects.requireNonNull(id, "id"));
    }

    /**
     * 入职命令。
     */
    public static Employee onboard(EmployeeId id, String employeeNo, String name,
                                   String department, String position, LocalDate hireDate) {
        Employee employee = new Employee(Objects.requireNonNull(id, "id"));
        employee.raise(new EmployeeOnboarded(id,
                requireText(employeeNo, "employeeNo"),
                requireText(name, "name"),
                requireText(department, "department"),
                requireText(position, "position"),
                Objects.requireNonNull(hireDate, "hireDate")));
        return employee;
    }

    /**
     * 事件应用：唯一的字段写入点。
     */
    protected void apply(EmployeeOnboarded event) {
        this.employeeNo = event.employeeNo();
        this.name = event.name();
        this.department = event.department();
        this.position = event.position();
        this.hireDate = event.hireDate();
    }

    public String employeeNo() {
        return employeeNo;
    }

    public String name() {
        return name;
    }

    public String department() {
        return department;
    }

    public String position() {
        return position;
    }

    public LocalDate hireDate() {
        return hireDate;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
