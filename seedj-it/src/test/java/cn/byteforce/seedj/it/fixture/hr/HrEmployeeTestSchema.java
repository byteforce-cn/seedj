package cn.byteforce.seedj.it.fixture.hr;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Maskers;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;

/**
 * 测试侧员工 schema：声明由测试拥有，用来发现“生产改了、测试没改”。
 *
 * <p>与生产 schema 的一致性由 {@code HrEmployeeSchemaConformanceTest} 保证；这里刻意重复
 * 一份声明，而不是引用生产代码——否则测试会跟着生产一起漂移。</p>
 */
public final class HrEmployeeTestSchema {

    private HrEmployeeTestSchema() {}

    public static ObjectSchema schema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .field(FieldDefinition.required("employee_no", FieldType.TEXT)
                        .withFilterable().withSortable().withIndexed())
                .field(FieldDefinition.required("name", FieldType.TEXT))
                .field(FieldDefinition.required("status", FieldType.TEXT).withFilterable())
                .field("hire_date", FieldType.DATE)
                .field("employment_type", FieldType.TEXT)
                .field("work_location", FieldType.TEXT)
                .field(new FieldDefinition("phone", FieldType.TEXT, false, false, false, false,
                        false, false, Privacy.SENSITIVE, Maskers.last4()))
                .writeOnly("id_number_hash", FieldType.TEXT)
                .field(FieldDefinition.of("org_unit_id", FieldType.TEXT)
                        .withFilterable().withIndexed())
                .build();
    }
}
