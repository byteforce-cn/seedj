package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import cn.byteforce.seedj.it.fixture.hr.HrEmployeeTestSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成物与运行时契约的一致性：
 *
 * <ul>
 *   <li>{@code @SeedjObject} 生成的 schema 与测试侧独立声明完全一致（发现漂移）；</li>
 *   <li>生成的建表候选与运行时 {@code TemporalDdl} 模板逐字节一致（发现模板分叉）。</li>
 * </ul>
 */
class HrEmployeeSchemaConformanceTest {

    @Test
    @DisplayName("生产 schema 与测试侧 schema 一致（字段、类型、过滤/排序/索引/隐私）")
    void productionSchemaMatchesTestSchema() {
        ObjectSchema production = HrEmployeeFields.schema();
        ObjectSchema test = HrEmployeeTestSchema.schema();

        assertThat(production.objectType()).isEqualTo(test.objectType());
        assertThat(production.tableName()).isEqualTo(test.tableName());
        assertThat(signatures(production)).containsExactlyElementsOf(signatures(test));
        assertThat(production.fields()).allSatisfy(field -> {
            if (field.privacy() == cn.byteforce.seedj.domain.Privacy.SENSITIVE) {
                assertThat(field.masker()).as("sensitive field %s has a masker", field.name())
                        .isNotNull();
            }
        });
    }

    @Test
    @DisplayName("生成的建表候选与 TemporalDdl 模板一致")
    void generatedDdlMatchesTemporalTemplate() throws IOException {
        String generated = resource("META-INF/seedj/ddl/hr_employee-create.sql");
        String body = generated.substring(generated.indexOf("CREATE TABLE"));
        String expected = TemporalDdl.createTable(
                TemporalTable.from(HrEmployeeFields.schema()));

        assertThat(body).isEqualTo(expected);
    }

    @Test
    @DisplayName("生成的 OpenAPI 片段登记对象类型、表名与写入专用字段")
    void generatedOpenApiFragment() throws IOException {
        String openapi = resource("META-INF/seedj/openapi/employee.yaml");

        assertThat(openapi).contains("objectType: employee");
        assertThat(openapi).contains("table: hr_employee");
        assertThat(openapi).contains("id_number_hash:\n          type: string\n"
                + "          writeOnly: true");
    }

    private static List<String> signatures(ObjectSchema schema) {
        return schema.fields().stream().map(HrEmployeeSchemaConformanceTest::signature).toList();
    }

    private static String signature(FieldDefinition field) {
        return field.name() + "|" + field.type() + "|" + field.required() + "|"
                + field.writeOnly() + "|" + field.eventVisible() + "|" + field.filterable()
                + "|" + field.sortable() + "|" + field.indexed() + "|" + field.privacy()
                + "|" + (field.masker() == null ? "none" : "masker");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = HrEmployeeSchemaConformanceTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing generated resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
