package cn.byteforce.seedj.infra.jdbc.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 列约束从 schema 透传到物理表与 DDL。 */
class TemporalDdlConstraintsTest {

    @Test
    @DisplayName("显式列约束进入 DDL")
    void ddlCarriesColumnConstraints() {
        TemporalTable table =
                new TemporalTable(
                        "employee",
                        "hr_employee",
                        Map.of("employee_no", "TEXT", "status", "TEXT"),
                        Set.of(),
                        Set.of("status"),
                        Map.of("employee_no", FieldType.TEXT, "status", FieldType.TEXT),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "employee_no",
                                        new TemporalTable.ColumnConstraint(true, List.of(), true),
                                "status",
                                        new TemporalTable.ColumnConstraint(
                                                true,
                                                List.of("status IN ('active','terminated')"),
                                                false)));
        String ddl = TemporalDdl.createTable(table);

        assertThat(ddl).contains("employee_no TEXT NOT NULL");
        assertThat(ddl).contains("status TEXT NOT NULL CHECK (status IN ('active','terminated'))");
        assertThat(ddl)
                .contains(
                        "CREATE UNIQUE INDEX uq_hr_employee_employee_no ON hr_employee (employee_no)"
                                + " WHERE superseded_at IS NULL");
    }

    @Test
    @DisplayName("schema 派生：required→NOT NULL、enum/pattern→CHECK、unique→唯一索引、decimal→精度")
    void derivesConstraintsFromSchema() {
        ObjectSchema schema =
                ObjectSchema.builder("employee", "hr_employee")
                        .field(
                                new FieldDefinition(
                                        "employee_no",
                                        FieldType.TEXT,
                                        true,
                                        false,
                                        true,
                                        true,
                                        true,
                                        true,
                                        Privacy.PUBLIC,
                                        null,
                                        new FieldDefinition.Constraints(
                                                Set.of(),
                                                "^EMP-[0-9]{6,}$",
                                                null,
                                                null,
                                                true,
                                                null,
                                                null)))
                        .field(
                                new FieldDefinition(
                                        "status",
                                        FieldType.TEXT,
                                        true,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false,
                                        Privacy.PUBLIC,
                                        null,
                                        new FieldDefinition.Constraints(
                                                Set.of("active", "terminated"),
                                                null,
                                                null,
                                                null,
                                                false,
                                                null,
                                                null)))
                        .field(
                                new FieldDefinition(
                                        "salary",
                                        FieldType.DECIMAL,
                                        false,
                                        false,
                                        true,
                                        false,
                                        false,
                                        false,
                                        Privacy.PUBLIC,
                                        null,
                                        new FieldDefinition.Constraints(
                                                Set.of(), null, null, null, false, 12, 2)))
                        .build();

        TemporalTable table = TemporalTable.from(schema);
        String ddl = TemporalDdl.createTable(table);

        assertThat(table.columns()).containsEntry("salary", "NUMERIC(12,2)");
        assertThat(ddl)
                .contains("employee_no TEXT NOT NULL CHECK (employee_no ~ '^EMP-[0-9]{6,}$')");
        assertThat(ddl).contains("status TEXT NOT NULL CHECK (status IN ('active', 'terminated'))");
        assertThat(ddl).contains("salary NUMERIC(12,2)");
        assertThat(ddl).contains("CREATE UNIQUE INDEX uq_hr_employee_employee_no");
    }

    @Test
    @DisplayName("required 生成 NOT NULL，旧装配保持无约束")
    void requiredProducesNotNull() {
        ObjectSchema schema =
                ObjectSchema.builder("employee", "hr_employee")
                        .required("name", FieldType.TEXT)
                        .field("nickname", FieldType.TEXT)
                        .build();
        String ddl = TemporalDdl.createTable(TemporalTable.from(schema));

        assertThat(ddl).contains("    name TEXT NOT NULL,");
        assertThat(ddl).contains("    nickname TEXT,");
    }

    @Test
    @DisplayName("decimal 精度由约束决定")
    void decimalTypeUsesDeclaredPrecision() {
        assertThat(FieldType.DECIMAL.sqlType(18, 2)).isEqualTo("NUMERIC(18,2)");
        assertThat(FieldType.DECIMAL.sqlType(9, 0)).isEqualTo("NUMERIC(9,0)");
        assertThat(FieldType.DECIMAL.sqlType(null, null)).isEqualTo("NUMERIC(18,4)");
        assertThat(FieldType.INTEGER.sqlType(18, 2)).isEqualTo("INTEGER");
        assertThat(new BigDecimal("12.34").scale()).isEqualTo(2);
    }
}
