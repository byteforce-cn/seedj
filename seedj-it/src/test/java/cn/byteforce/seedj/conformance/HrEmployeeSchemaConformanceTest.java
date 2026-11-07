package cn.byteforce.seedj.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成物与运行时契约的一致性：
 *
 * <ul>
 *   <li>生成的建表候选与运行时 {@code TemporalDdl} 模板逐字节一致（发现模板分叉）；</li>
 *   <li>生成的 OpenAPI 片段登记对象类型、表名与写入专用字段。</li>
 * </ul>
 *
 * <p>业务 schema 只有 {@link HrEmployeeFields} 一份声明，不再维护测试侧副本。</p>
 */
class HrEmployeeSchemaConformanceTest {

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
