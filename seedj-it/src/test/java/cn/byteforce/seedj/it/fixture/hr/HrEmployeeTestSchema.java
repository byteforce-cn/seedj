package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;

/**
 * 测试侧员工 schema：与 HR 样板声明一致，但由测试拥有。
 *
 * <p>测试不读取生产 schema，才能在字段声明漂移时发现“生产改了、测试没改”或反之；两者一致
 * 由 HR 的 schema conformance 测试保证。</p>
 */
public final class HrEmployeeTestSchema {

    private HrEmployeeTestSchema() {}

    public static ObjectSchema schema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .field(FieldDefinition.required("employee_no", FieldType.TEXT).withFilterable())
                .field(FieldDefinition.required("name", FieldType.TEXT))
                .field(FieldDefinition.required("status", FieldType.TEXT))
                .field("hire_date", FieldType.DATE)
                .field("employment_type", FieldType.TEXT)
                .field("work_location", FieldType.TEXT)
                .writeOnly("id_number_hash", FieldType.TEXT)
                .field("org_unit_id", FieldType.TEXT)
                .build();
    }
}
