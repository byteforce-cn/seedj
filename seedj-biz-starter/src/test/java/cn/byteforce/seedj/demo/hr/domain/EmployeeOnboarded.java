package cn.byteforce.seedj.demo.hr.domain;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import java.time.LocalDate;

/**
 * 员工已入职。
 *
 * <p>事件只描述"发生了什么"，字段是入职那一刻的事实快照；后续变更用新事件表达， 不回改历史。
 *
 * @param employeeId 员工标识
 * @param employeeNo 工号（业务编码，与标识分离）
 * @param name 姓名
 * @param department 部门
 * @param position 岗位
 * @param hireDate 入职日期
 * @author ByteForce
 * @since 1.0.0
 */
@EventType("hr.employee.onboarded")
public record EmployeeOnboarded(
        EmployeeId employeeId,
        String employeeNo,
        String name,
        String department,
        String position,
        LocalDate hireDate)
        implements DomainEvent {}
