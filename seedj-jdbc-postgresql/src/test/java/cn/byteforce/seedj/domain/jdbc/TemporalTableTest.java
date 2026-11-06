package cn.byteforce.seedj.domain.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象表映射的标识符/类型校验：SQL 拼接前的最后一道防线。
 */
class TemporalTableTest {

    @Test
    void rejectsMetaColumnCollision() {
        assertThatThrownBy(() -> new TemporalTable("probe", "probe_object",
                Map.of("version", "TEXT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsNonIdentifierTableName() {
        assertThatThrownBy(() -> new TemporalTable("probe", "probe-object",
                Map.of("name", "TEXT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void rejectsUnsafeColumnType() {
        assertThatThrownBy(() -> new TemporalTable("probe", "probe_object",
                Map.of("name", "TEXT; DROP TABLE probe_object")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column type");
    }

    @Test
    void rejectsDuplicateObjectType() {
        TemporalTable table = new TemporalTable("probe", "probe_object",
                Map.of("name", "TEXT"));
        assertThatThrownBy(() -> new TemporalTables(List.of(table, table)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate objectType");
    }

    @Test
    void unknownTypeIsNamedInTheError() {
        TemporalTables tables = new TemporalTables(List.of(
                new TemporalTable("probe", "probe_object", Map.of("name", "TEXT"))));
        assertThatThrownBy(() -> tables.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
