package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.codegen.SeedjField;
import cn.byteforce.seedj.codegen.SeedjObject;
import cn.byteforce.seedj.domain.Privacy;

import java.time.LocalDate;

/**
 * 员工字段的唯一来源：schema、DDL 候选与 OpenAPI 都由 {@code seedj-codegen} 从这里生成。
 *
 * <p>加字段只改这个 record，再把生成的迁移候选审阅后合并进新版本（V2、V3…）；
 * 已发布的 V1 不再修改。</p>
 *
 * @param employeeNo     工号
 * @param name           姓名
 * @param status         状态
 * @param hireDate       入职日期
 * @param employmentType 雇佣类型
 * @param workLocation   工作地点
 * @param phone          联系电话（敏感，默认打码）
 * @param idNumberHash   证件号散列（写入专用）
 * @param orgUnitId      组织单元
 * @author ByteForce
 * @since 1.0.0
 */
@SeedjObject(objectType = "employee", table = "hr_employee", view = true)
public record HrEmployeeFields(
        @SeedjField(required = true, filterable = true, sortable = true, indexed = true)
        String employeeNo,
        @SeedjField(required = true)
        String name,
        @SeedjField(required = true, filterable = true)
        String status,
        @SeedjField
        LocalDate hireDate,
        @SeedjField
        String employmentType,
        @SeedjField
        String workLocation,
        @SeedjField(privacy = Privacy.SENSITIVE, masker = MaskerName.LAST4)
        String phone,
        @SeedjField(writeOnly = true)
        String idNumberHash,
        @SeedjField(filterable = true, indexed = true)
        String orgUnitId) {

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
