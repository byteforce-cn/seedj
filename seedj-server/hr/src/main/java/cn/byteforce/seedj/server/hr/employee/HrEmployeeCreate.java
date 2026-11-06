package cn.byteforce.seedj.server.hr.employee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * HR 样板命令载荷：员工建档。
 *
 * @param name           姓名
 * @param hireDate       入职日期（yyyy-MM-dd）
 * @param employmentType 雇佣类型
 * @param workLocation   工作地点
 * @param idNumber       证件号原始值（writeOnly）
 * @author ByteForce
 * @since 1.0.0
 */
public record HrEmployeeCreate(
        String name,
        @JsonProperty("hire_date") LocalDate hireDate,
        @JsonProperty("employment_type") String employmentType,
        @JsonProperty("work_location") String workLocation,
        @JsonProperty("id_number") String idNumber) {
}
