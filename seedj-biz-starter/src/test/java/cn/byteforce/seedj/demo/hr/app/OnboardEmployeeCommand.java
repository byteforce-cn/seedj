package cn.byteforce.seedj.demo.hr.app;

import cn.byteforce.seedj.cqrs.Command;
import cn.byteforce.seedj.demo.hr.domain.EmployeeId;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 入职命令。
 *
 * <p>携带<b>目标员工标识</b>：命令在边界处（{@link HrCommandService}）由
 * {@code IdGenerator} 分配标识后构建，因此命令对象一旦存在就是完整可执行的——
 * 模板可以在加载聚合之前就完成加锁与 CAS 键的确定。
 *
 * @param employeeId 目标员工标识
 * @param employeeNo 工号
 * @param name 姓名
 * @param department 部门
 * @param position 岗位
 * @param hireDate 入职日期
 * @author ByteForce
 * @since 1.0.0
 */
public record OnboardEmployeeCommand(
        EmployeeId employeeId,
        String employeeNo,
        String name,
        String department,
        String position,
        LocalDate hireDate) implements Command<EmployeeId> {

    public OnboardEmployeeCommand {
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(hireDate, "hireDate");
    }

    @Override
    public EmployeeId aggregateId() {
        return employeeId;
    }
}
