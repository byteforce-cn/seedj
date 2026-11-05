package cn.byteforce.seedj.demo.hr.domain;

import cn.byteforce.seedj.domain.Repository;

/**
 * 员工仓储：只有按标识加载与保存两件事。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EmployeeRepository extends Repository<Employee, EmployeeId> {
}
