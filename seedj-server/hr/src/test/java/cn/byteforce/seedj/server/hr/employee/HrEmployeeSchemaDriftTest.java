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
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1 漂移门禁：冻结的 literal SQL 必须与生成的 schema 完全一致。
 *
 * <p>加字段却忘记写 V2 时，这里会失败并列出缺失/多余的列；列类型变化同样失败。
 * 类型按 {@link FieldType#sqlType()} 的完整写法比较（含 {@code NUMERIC(18,4)} 与
 * {@code TIMESTAMPTZ(6)} 的精度），而不是只比基类型；反向也检查：表里多出未声明的
 * 业务列同样算漂移，防止手工改表后 schema 悄悄落后。</p>
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
    @DisplayName("V1 的领域列与生成 schema 完全一致（名称与完整类型）")
    void tableColumnsMatchGeneratedSchema() {
        assertNoDrift("hr_employee", schema);
    }

    @Test
    @DisplayName("类型精度漂移被识别：NUMERIC(10,2) 不等于 NUMERIC(18,4)")
    void typePrecisionDriftIsDetected() {
        jdbc.execute("DROP TABLE IF EXISTS drift_probe");
        jdbc.execute("CREATE TABLE drift_probe (amount NUMERIC(10,2))");
        ObjectSchema probe = ObjectSchema.builder("drift_probe", "drift_probe")
                .field("amount", FieldType.DECIMAL)
                .build();

        assertThatThrownBy(() -> assertNoDrift("drift_probe", probe))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("numeric(18,4)");
    }

    private static void assertNoDrift(String tableName, ObjectSchema schema) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT a.attname AS column_name,"
                        + " format_type(a.atttypid, a.atttypmod) AS data_type"
                        + " FROM pg_attribute a"
                        + " WHERE a.attrelid = to_regclass(?) AND a.attnum > 0"
                        + " AND NOT a.attisdropped"
                        + " ORDER BY a.attnum", tableName)) {
            actual.put((String) row.get("column_name"), (String) row.get("data_type"));
        }
        actual.keySet().removeAll(ObjectSchema.META_FIELDS);

        Map<String, String> declared = new LinkedHashMap<>();
        for (FieldDefinition field : schema.fields()) {
            declared.put(field.name(), expectedSqlType(field.type()));
        }

        assertThat(actual)
                .as("表 %s 必须是生成 schema 的物理映射；加字段请新增 V2，不要改 V1", tableName)
                .isEqualTo(declared);
    }

    /**
     * {@link FieldType#sqlType()} 与 PostgreSQL {@code format_type} 的写法差异只有
     * {@code timestamptz(n)} → {@code timestamp(n) with time zone}。
     */
    private static String expectedSqlType(FieldType type) {
        String sqlType = type.sqlType().toLowerCase(Locale.ROOT);
        if (sqlType.startsWith("timestamptz(")) {
            return "timestamp" + sqlType.substring("timestamptz".length())
                    + " with time zone";
        }
        return sqlType;
    }
}
