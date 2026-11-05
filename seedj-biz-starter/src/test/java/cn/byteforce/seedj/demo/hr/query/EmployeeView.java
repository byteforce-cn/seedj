package cn.byteforce.seedj.demo.hr.query;

import cn.byteforce.seedj.demo.hr.domain.EmployeeId;

import java.time.LocalDate;

/**
 * 员工目录的读模型行。
 *
 * <p>读模型按查询需要裁剪字段，不与聚合共享类：聚合加字段不会强迫投影表迁移。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public record EmployeeView(
        EmployeeId employeeId,
        String employeeNo,
        String name,
        String department,
        String position,
        LocalDate hireDate) {
}
