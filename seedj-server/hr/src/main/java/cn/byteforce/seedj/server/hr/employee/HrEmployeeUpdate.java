package cn.byteforce.seedj.server.hr.employee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * HR 样板命令载荷：员工更新。
 *
 * @param employeeId     员工 ID
 * @param name           姓名（可选）
 * @param employmentType 雇佣类型（可选）
 * @param workLocation   工作地点（可选）
 * @param orgUnitId      组织单元（可选）
 * @param idNumber       证件号原始值（可选，writeOnly）
 * @author ByteForce
 * @since 1.0.0
 */
public record HrEmployeeUpdate(
        @JsonProperty("employee_id") UUID employeeId,
        String name,
        @JsonProperty("employment_type") String employmentType,
        @JsonProperty("work_location") String workLocation,
        @JsonProperty("org_unit_id") String orgUnitId,
        @JsonProperty("id_number") String idNumber) {
}
