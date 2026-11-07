package cn.byteforce.seedj.server.hr.employee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.UUID;

/**
 * HR 样板命令载荷：员工建档。
 *
 * @param name           姓名
 * @param hireDate       入职日期（yyyy-MM-dd，必填）
 * @param employmentType 雇佣类型（full_time/part_time/intern/contract）
 * @param workLocation   工作地点
 * @param contact        联系方式
 * @param orgUnitId      组织单元（UUID，读范围使用）
 * @param idType         证件类型（national_id/passport）
 * @param idNumber       证件号原始值（writeOnly）
 * @author ByteForce
 * @since 1.0.0
 */
public record HrEmployeeCreate(
        String name,
        @JsonProperty("hire_date") LocalDate hireDate,
        @JsonProperty("employment_type") String employmentType,
        @JsonProperty("work_location") String workLocation,
        @JsonProperty("contact") String contact,
        @JsonProperty("org_unit_id") UUID orgUnitId,
        @JsonProperty("id_type") String idType,
        @JsonProperty("id_number") String idNumber) {
}
