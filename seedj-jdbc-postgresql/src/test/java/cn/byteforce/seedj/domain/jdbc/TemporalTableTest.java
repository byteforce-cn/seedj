package cn.byteforce.seedj.domain.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** schema → 物理表派生：SQL 类型、写入专用列、可过滤列。 */
class TemporalTableTest {

    @Test
    @DisplayName("from(schema) 映射 SQL 类型并保留过滤/写入语义")
    void derivesColumnsAndFlagsFromSchema() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("employee_no", FieldType.TEXT, true, false, true, true))
                .field("hire_date", FieldType.DATE)
                .field("ref_id", FieldType.UUID)
                .field("score", FieldType.DECIMAL)
                .writeOnly("id_number_hash", FieldType.TEXT)
                .build();

        TemporalTable table = TemporalTable.from(schema);

        assertThat(table.columns()).containsExactly(
                Map.entry("employee_no", "TEXT"),
                Map.entry("hire_date", "DATE"),
                Map.entry("ref_id", "UUID"),
                Map.entry("score", "NUMERIC(18,4)"),
                Map.entry("id_number_hash", "TEXT"));
        assertThat(table.fieldTypes()).containsEntry("hire_date", FieldType.DATE);
        assertThat(table.writeOnlyColumns()).containsExactly("id_number_hash");
        assertThat(table.filterableColumns()).containsExactly("employee_no");
    }

    @Test
    @DisplayName("旧四参构造器默认全部列可过滤、无字段类型")
    void legacyConstructorKeepsOldDefaults() {
        TemporalTable table = new TemporalTable("probe", "probe_object", Map.of("name", "TEXT"));

        assertThat(table.filterableColumns()).containsExactly("name");
        assertThat(table.fieldTypes()).isEmpty();
    }
}
