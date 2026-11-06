package cn.byteforce.seedj.server.hr.employee;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.module.ObjectModules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 漂移门禁：冻结的 literal SQL 必须与生成的 schema 完全一致。
 *
 * <p>加字段却忘记写 V2 时，这里会失败并列出缺失/多余的列；列类型变化同样失败。
 * 反向也检查：表里多出未声明的业务列同样算漂移，防止手工改表后 schema 悄悄落后。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Testcontainers
class HrEmployeeSchemaDriftTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static ObjectSchema schema;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new TemporalSchemaCapability(runner).initialize();
        ObjectModules modules = ObjectModules.create();
        modules.object(HrEmployeeFields.schema()).readPermission("hr.employee.read");
        new HrEmployeeSchemaCapability(runner, modules.freeze()).initialize();
        schema = HrEmployeeFields.schema();
    }

    @Test
    @DisplayName("V1 的领域列与生成 schema 完全一致（名称与类型）")
    void tableColumnsMatchGeneratedSchema() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'hr_employee'"
                        + " ORDER BY ordinal_position")) {
            actual.put((String) row.get("column_name"), (String) row.get("data_type"));
        }
        actual.keySet().removeAll(ObjectSchema.META_FIELDS);

        Map<String, String> declared = new LinkedHashMap<>();
        for (FieldDefinition field : schema.fields()) {
            declared.put(field.name(), informationSchemaType(field.type()));
        }

        assertThat(actual)
                .as("V1 必须是生成 schema 的物理映射；加字段请新增 V2，不要改 V1")
                .isEqualTo(declared);
    }

    private static String informationSchemaType(FieldType type) {
        return switch (type) {
            case TEXT -> "text";
            case INTEGER -> "integer";
            case LONG -> "bigint";
            case DECIMAL -> "numeric";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case TIMESTAMP -> "timestamp with time zone";
            case UUID -> "uuid";
        };
    }
}
