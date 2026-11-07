package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** schema 声明约束与载荷校验。 */
class ObjectSchemaTest {

    private static final ObjectSchema EMPLOYEE = ObjectSchema.builder("employee", "hr_employee")
            .field(FieldDefinition.required("employee_no", FieldType.TEXT).withFilterable())
            .field("hire_date", FieldType.DATE)
            .field("ref_id", FieldType.UUID)
            .writeOnly("id_number_hash", FieldType.TEXT)
            .build();

    @Test
    @DisplayName("声明必须是合法标识符")
    void namesMustBeIdentifiers() {
        assertThatThrownBy(() -> ObjectSchema.builder("Employee", "hr_employee").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectType");
        assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr-employee").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
        assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr_employee")
                .field("HireDate", FieldType.DATE).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field name");
    }

    @Test
    @DisplayName("重复字段与元字段冲突都失败")
    void duplicateAndMetaFieldsAreRejected() {
        assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr_employee")
                .field("name", FieldType.TEXT)
                .field("name", FieldType.TEXT)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate field");

        assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr_employee")
                .field("version", FieldType.LONG)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meta field");
    }

    @Test
    @DisplayName("writeOnly 与 eventVisible 互斥")
    void writeOnlyCannotBeEventVisible() {
        assertThatThrownBy(() -> new FieldDefinition("secret", FieldType.TEXT, false, true, true,
                        false, false, false, Privacy.PUBLIC, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("writeOnly");
    }

    @Test
    @DisplayName("required / unknown / 非法值都映射为无效载荷")
    void validateRejectsMissingUnknownAndInvalid() {
        assertThatThrownBy(() -> EMPLOYEE.validate(Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                    assertThat(error.reason()).contains("employee_no");
                });

        assertThatThrownBy(() -> EMPLOYEE.validate(
                Map.of("employee_no", "E-1", "unknown", "x")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.reason()).contains("unknown"));

        assertThatThrownBy(() -> EMPLOYEE.validate(
                Map.of("employee_no", "E-1", "hire_date", "not-a-date")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.reason()).contains("hire_date"));
    }

    @Test
    @DisplayName("校验结果按字段类型转换")
    void validateCoercesTypes() {
        UUID refId = UUID.randomUUID();

        Map<String, Object> typed = EMPLOYEE.validate(Map.of(
                "employee_no", "E-1", "hire_date", "2026-10-01", "ref_id", refId.toString()));

        assertThat(typed).containsEntry("hire_date", LocalDate.parse("2026-10-01"))
                .containsEntry("ref_id", refId);
    }

    @Test
    @DisplayName("事件白名单只含 eventVisible，写入专用字段被排除")
    void eventFieldsExcludeWriteOnly() {
        assertThat(EMPLOYEE.eventFields()).containsExactly("employee_no", "hire_date", "ref_id");
        assertThat(EMPLOYEE.writeOnlyFields()).containsExactly("id_number_hash");
        assertThat(EMPLOYEE.filterableFields()).containsExactly("employee_no");
    }

    @Test
    @DisplayName("事件载荷只保留 eventVisible 字段，未知字段失败关闭")
    void eventPayloadFiltersWriteOnlyAndRejectsUnknown() {
        Map<String, Object> payload = Map.of(
                "employee_no", "E-1",
                "id_number_hash", "secret",
                "unknown", "x");

        assertThatThrownBy(() -> EMPLOYEE.eventPayload(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");

        Map<String, Object> filtered = EMPLOYEE.eventPayload(
                Map.of("employee_no", "E-1", "id_number_hash", "secret"));
        assertThat(filtered).containsExactly(Map.entry("employee_no", "E-1"));
    }

    @Test
    @DisplayName("敏感字段不得进入事件白名单")
    void sensitiveFieldCannotBeEventVisible() {
        assertThatThrownBy(() -> ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("id_number", FieldType.TEXT, false, false, true,
                        false, false, false, Privacy.SENSITIVE, null))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVisible");
    }

    @Test
    @DisplayName("注册表拒绝重复类型，require 未知类型失败")
    void registryRejectsDuplicatesAndUnknownTypes() {
        ObjectSchemaRegistry registry = new ObjectSchemaRegistry(List.of(EMPLOYEE));

        assertThat(registry.require("employee")).isSameAs(EMPLOYEE);
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> new ObjectSchemaRegistry(List.of(EMPLOYEE, EMPLOYEE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate objectType");
        assertThat(ObjectSchemaRegistry.empty().all()).isEmpty();
    }

    @Test
    @DisplayName("表名、字段查询与必填/可排序字段集合")
    void accessorsAndDerivedFieldSets() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .required("employee_no", FieldType.TEXT)
                .filterable("org_unit_id", FieldType.TEXT)
                .field(FieldDefinition.of("status", FieldType.TEXT).withSortable())
                .build();

        assertThat(schema.tableName()).isEqualTo("hr_employee");
        assertThat(schema.field("employee_no")).isPresent();
        assertThat(schema.field(null)).isEmpty();
        assertThat(schema.field("missing")).isEmpty();
        assertThatThrownBy(() -> schema.requireField("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field: missing");
        assertThat(schema.requiredFields()).containsExactly("employee_no");
        assertThat(schema.sortableFields()).containsExactly("status");
        assertThat(schema.filterableFields()).containsExactly("org_unit_id");
        assertThat(schema.validate(Map.of("employee_no", "E-1"))).containsEntry("employee_no",
                "E-1");
        assertThat(schema.fieldNames()).containsExactly("employee_no", "org_unit_id", "status");
    }

    @Test
    @DisplayName("注册表空查找与未知类型 require 失败")
    void registryLookupGuards() {
        ObjectSchemaRegistry registry = new ObjectSchemaRegistry(List.of(EMPLOYEE));

        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.types()).containsExactly("employee");
        assertThat(registry.all()).containsExactly(EMPLOYEE);
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown object type: missing")
                .hasMessageContaining("employee");
    }
}
