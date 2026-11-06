package cn.byteforce.seedj.codegen.processor;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Privacy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 渲染结果：源码、OpenAPI 与迁移候选。 */
class SchemaRendererTest {

    private static final ObjectModel MODEL = new ObjectModel(
            "employee", "hr_employee", "fixture", "EmployeeFields",
            List.of(
                    new FieldModel("employee_no", FieldType.TEXT, true, false, true, true, true,
                            Privacy.PUBLIC, MaskerName.NONE),
                    new FieldModel("phone", FieldType.TEXT, false, false, false, false, false,
                            Privacy.SENSITIVE, MaskerName.LAST4),
                    new FieldModel("id_number_hash", FieldType.TEXT, false, true, false, false,
                            false, Privacy.PUBLIC, MaskerName.NONE),
                    new FieldModel("hire_date", FieldType.DATE, false, false, false, false, false,
                            Privacy.PUBLIC, MaskerName.NONE)));

    @Test
    @DisplayName("生成源码：包内可见、字段顺序与声明一致、打码器用运行时工厂")
    void javaSource() {
        String source = SchemaRenderer.javaSource(MODEL);

        assertThat(source).contains("package fixture;");
        assertThat(source).contains("final class EmployeeFieldsSchema {");
        assertThat(source).contains("ObjectSchema.builder(\"employee\", \"hr_employee\")");
        assertThat(source).contains("new FieldDefinition(\"employee_no\", FieldType.TEXT, true,"
                + " false, true, true, true, true, Privacy.PUBLIC, null)");
        assertThat(source).contains("cn.byteforce.seedj.domain.Maskers.last4()");
        assertThat(source).contains("new FieldDefinition(\"id_number_hash\", FieldType.TEXT,"
                + " false, true, false, false, false, false, Privacy.PUBLIC, null)");
    }

    @Test
    @DisplayName("OpenAPI：必填、写入专用与隐私/打码扩展")
    void openApi() {
        String yaml = SchemaRenderer.openApi(MODEL);

        assertThat(yaml).contains("openapi: 3.1.0");
        assertThat(yaml).contains("        - employee_no");
        assertThat(yaml).contains("        id_number_hash:\n          type: string\n"
                + "          writeOnly: true");
        assertThat(yaml).contains("          x-privacy: SENSITIVE");
        assertThat(yaml).contains("          x-masker: LAST4");
        assertThat(yaml).contains("          format: date");
    }

    @Test
    @DisplayName("建表候选：双时间轴元字段、排他约束、索引与 as-of 函数")
    void createSql() {
        String sql = SchemaRenderer.createSql(MODEL);

        assertThat(sql).contains("-- Generated candidate by seedj-codegen");
        assertThat(sql).contains("CREATE TABLE hr_employee (");
        assertThat(sql).contains("    id              eis_id        NOT NULL,");
        assertThat(sql).contains("    employee_no TEXT,");
        assertThat(sql).contains("    hire_date DATE,");
        assertThat(sql).contains("CONSTRAINT ex_hr_employee_valid_overlap EXCLUDE USING gist");
        assertThat(sql).contains("CREATE INDEX idx_hr_employee_employee_no"
                + " ON hr_employee (employee_no) WHERE superseded_at IS NULL;");
        assertThat(sql).contains("CREATE FUNCTION hr_employee_as_of_valid");
        assertThat(sql).contains("    phone TEXT,");
        assertThat(sql).doesNotContain("unknown_column");
    }

    @Test
    @DisplayName("追加列候选：每个字段一条 ADD COLUMN IF NOT EXISTS")
    void alterSql() {
        String sql = SchemaRenderer.alterSql(MODEL);

        assertThat(sql).contains("ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS"
                + " employee_no TEXT;");
        assertThat(sql).contains("ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS"
                + " phone TEXT;");
        assertThat(sql).contains("ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS"
                + " hire_date DATE;");
    }
}
