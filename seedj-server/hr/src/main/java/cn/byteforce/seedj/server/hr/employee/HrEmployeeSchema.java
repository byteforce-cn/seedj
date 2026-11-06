package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;

/**
 * 员工对象的唯一字段声明：表、DDL、事件白名单、读投影脱敏与命令校验都由它派生。
 *
 * <p>加字段只改这里 + 对应 command record；不要再手工同步 TemporalTable、fields helper、
 * 事件白名单与测试 fixture。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HrEmployeeSchema {

    /**
     * 员工 schema。
     *
     * @return 对象 schema
     */
    public ObjectSchema schema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("employee_no", FieldType.TEXT, true, false, true, true))
                .field(new FieldDefinition("name", FieldType.TEXT, true, false, true, false))
                .field(new FieldDefinition("status", FieldType.TEXT, true, false, true, false))
                .field("hire_date", FieldType.DATE)
                .field("employment_type", FieldType.TEXT)
                .field("work_location", FieldType.TEXT)
                .writeOnly("id_number_hash", FieldType.TEXT)
                .field("org_unit_id", FieldType.TEXT)
                .build();
    }
}
