package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.codegen.SeedjField;
import cn.byteforce.seedj.codegen.SeedjObject;
import cn.byteforce.seedj.domain.Privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 员工字段的唯一来源：schema、DDL 候选与 OpenAPI 都由 {@code seedj-codegen} 从这里生成。
 *
 * <p>字段集合与约束对齐 eis {@code extensions/hr/objects/employee.schema.json}；
 * 加字段只改这个 record，再把生成的迁移候选审阅后合并进新版本（V3…）；已发布的 V1/V2
 * 不再修改。</p>
 *
 * @param employeeNo       工号（服务端生成，唯一）
 * @param name             姓名
 * @param status           状态
 * @param hireDate         入职日期
 * @param employmentType   雇佣类型
 * @param workLocation     工作地点
 * @param contact          联系方式（敏感，默认打码）
 * @param idType           证件类型
 * @param idNumberHash     证件号散列（写入专用）
 * @param orgUnitId        主组织单元
 * @param terminatedAt     离职时间
 * @param terminatedReason 离职原因
 * @author ByteForce
 * @since 1.0.0
 */
@SeedjObject(objectType = "employee", table = "hr_employee", view = true)
public record HrEmployeeFields(
        @SeedjField(required = true, filterable = true, sortable = true, indexed = true,
                unique = true, pattern = "^EMP-[0-9]{6,}$")
        String employeeNo,
        @SeedjField(required = true, minLength = 1)
        String name,
        @SeedjField(required = true, filterable = true,
                enumValues = {"pending_onboarding", "active", "terminated", "cancelled"})
        String status,
        @SeedjField(required = true)
        LocalDate hireDate,
        @SeedjField(enumValues = {"full_time", "part_time", "intern", "contract"})
        String employmentType,
        @SeedjField(minLength = 1)
        String workLocation,
        @SeedjField(privacy = Privacy.SENSITIVE, masker = MaskerName.LAST4)
        String contact,
        @SeedjField(enumValues = {"national_id", "passport"})
        String idType,
        @SeedjField(writeOnly = true, pattern = "^[0-9a-f]{64}$")
        String idNumberHash,
        @SeedjField(filterable = true, indexed = true)
        UUID orgUnitId,
        @SeedjField
        Instant terminatedAt,
        @SeedjField
        String terminatedReason) {

    /**
     * 生成的 schema（字段唯一来源）。
     *
     * @return 对象 schema
     */
    public static cn.byteforce.seedj.domain.ObjectSchema schema() {
        return HrEmployeeFieldsSchema.schema();
    }

    /**
     * 生成的读视图类型（元字段 + 可读字段，自动排除 writeOnly）。
     *
     * @return 视图类型
     */
    public static Class<HrEmployeeFieldsView> view() {
        return HrEmployeeFieldsView.class;
    }
}
