package cn.byteforce.seedj.codegen.processor;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Privacy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 值域约束从模型透传到 OpenAPI、DDL 与生成的 schema 源码。 */
class SchemaRendererConstraintsTest {

    private static final FieldDefinition.Constraints STATUS =
            new FieldDefinition.Constraints(
                    Set.of("terminated", "active"), null, null, null, false, null, null);

    private static final FieldDefinition.Constraints EMPLOYEE_NO =
            new FieldDefinition.Constraints(Set.of(), "^EMP-[0-9]{6,}$", 8, 32, true, null, null);

    private static ObjectModel model() {
        return new ObjectModel(
                "employee",
                "hr_employee",
                "fixture",
                "EmployeeFields",
                false,
                List.of(
                        new FieldModel(
                                "employee_no",
                                FieldType.TEXT,
                                "String",
                                true,
                                false,
                                true,
                                true,
                                true,
                                Privacy.PUBLIC,
                                MaskerName.NONE,
                                EMPLOYEE_NO),
                        new FieldModel(
                                "status",
                                FieldType.TEXT,
                                "String",
                                true,
                                false,
                                true,
                                false,
                                false,
                                Privacy.PUBLIC,
                                MaskerName.NONE,
                                STATUS),
                        new FieldModel(
                                "amount",
                                FieldType.DECIMAL,
                                "java.math.BigDecimal",
                                false,
                                false,
                                false,
                                false,
                                false,
                                Privacy.PUBLIC,
                                MaskerName.NONE,
                                new FieldDefinition.Constraints(
                                        Set.of(), null, null, null, false, 18, 2))));
    }

    @Test
    @DisplayName("OpenAPI 携带 enum/pattern/minLength/unique 与 decimal 精度")
    void openApiCarriesEnumAndPattern() {
        String yaml = SchemaRenderer.openApi(model());

        assertThat(yaml)
                .contains("          enum:\n            - active\n" + "            - terminated");
        assertThat(yaml).contains("          pattern: '^EMP-[0-9]{6,}$'");
        assertThat(yaml).contains("          minLength: 8");
        assertThat(yaml).contains("          maxLength: 32");
        assertThat(yaml).contains("          x-seedj-unique: true");
        assertThat(yaml).contains("          x-seedj-precision: 18");
        assertThat(yaml).contains("          x-seedj-scale: 2");
    }

    @Test
    @DisplayName("DDL 携带 NOT NULL、enum CHECK、pattern CHECK 与业务键唯一索引")
    void createSqlCarriesNotNullCheckAndUniqueIndex() {
        String sql = SchemaRenderer.createSql(model());

        assertThat(sql)
                .contains("    status TEXT NOT NULL CHECK (status IN ('active', 'terminated')),");
        assertThat(sql)
                .contains(
                        "    employee_no TEXT NOT NULL"
                                + " CHECK (employee_no ~ '^EMP-[0-9]{6,}$')"
                                + " CHECK (char_length(employee_no) >= 8)"
                                + " CHECK (char_length(employee_no) <= 32),");
        assertThat(sql).contains("    amount NUMERIC(18,2),");
        assertThat(sql)
                .contains(
                        "CREATE UNIQUE INDEX uq_hr_employee_employee_no"
                                + " ON hr_employee (employee_no) WHERE superseded_at IS NULL;");
    }

    @Test
    @DisplayName("生成的 schema 源码携带约束表达式，枚举顺序稳定")
    void javaSourceCarriesConstraints() {
        String source = SchemaRenderer.javaSource(model());

        assertThat(source)
                .contains(
                        "new FieldDefinition.Constraints(java.util.Set.of(),"
                                + " \"^EMP-[0-9]{6,}$\", 8, 32, true, null, null)");
        assertThat(source).contains("java.util.Set.of(\"active\", \"terminated\")");
        assertThat(source)
                .contains(
                        "new FieldDefinition.Constraints(java.util.Set.of(),"
                                + " null, null, null, false, 18, 2)");
    }
}
