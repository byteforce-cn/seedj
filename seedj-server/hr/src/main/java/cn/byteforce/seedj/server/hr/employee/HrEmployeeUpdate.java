package cn.byteforce.seedj.server.hr.employee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.UUID;

/**
 * HR 样板命令载荷：员工更新。
 *
 * @param employeeId       员工 ID
 * @param name             姓名（可选）
 * @param employmentType   雇佣类型（可选）
 * @param workLocation     工作地点（可选）
 * @param contact          联系方式（可选）
 * @param status           状态（可选，只允许前进迁移）
 * @param hireDate         入职日期（可选）
 * @param orgUnitId        组织单元（可选，UUID）
 * @param idType           证件类型（可选）
 * @param idNumber         证件号原始值（可选，writeOnly）
 * @param terminatedReason 离职原因（status=terminated 时记录）
 * @author ByteForce
 * @since 1.0.0
 */
public record HrEmployeeUpdate(
        @JsonProperty("employee_id") UUID employeeId,
        String name,
        @JsonProperty("employment_type") String employmentType,
        @JsonProperty("work_location") String workLocation,
        @JsonProperty("contact") String contact,
        @JsonProperty("status") String status,
        @JsonProperty("hire_date") LocalDate hireDate,
        @JsonProperty("org_unit_id") UUID orgUnitId,
        @JsonProperty("id_type") String idType,
        @JsonProperty("id_number") String idNumber,
        @JsonProperty("terminated_reason") String terminatedReason) {
}
