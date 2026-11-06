package cn.byteforce.seedj.infra.jdbc.domain;

import java.util.Map;

/**
 * 双时间轴对象表的 DDL 生成器（与规范模板逐条对齐）。
 *
 * <p>生成结果包含：10 个元字段、领域列、主键 {@code (id, recorded_at)}、
 * 半开区间 CHECK、单当前唯一索引、四维排他约束与三个时点索引。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalDdl {

    private TemporalDdl() {
    }

    /**
     * 生成一张对象表的建表语句。
     *
     * @param table 对象表定义
     * @return 可执行的 DDL（多语句，按顺序执行）
     */
    public static String createTable(TemporalTable table) {
        String name = table.tableName();
        StringBuilder sql = new StringBuilder(1024);
        sql.append("CREATE TABLE ").append(name).append(" (\n");
        sql.append("    id              eis_id        NOT NULL,\n");
        sql.append("    version         eis_version   NOT NULL DEFAULT 1,\n");
        sql.append("    valid_from      eis_timestamp NOT NULL,\n");
        sql.append("    valid_to        eis_timestamp,\n");
        sql.append("    recorded_at     eis_timestamp NOT NULL,\n");
        sql.append("    superseded_at   eis_timestamp,\n");
        sql.append("    created_by      eis_id        NOT NULL,\n");
        sql.append("    created_at      eis_timestamp NOT NULL,\n");
        sql.append("    updated_by      eis_id,\n");
        sql.append("    updated_at      eis_timestamp,\n");
        for (Map.Entry<String, String> column : table.columns().entrySet()) {
            sql.append("    ").append(column.getKey()).append(' ')
                    .append(column.getValue()).append(",\n");
        }
        sql.append("    CONSTRAINT pk_").append(name)
                .append(" PRIMARY KEY (id, recorded_at),\n");
        sql.append("    CONSTRAINT valid_range CHECK (valid_to IS NULL OR valid_to > valid_from),\n");
        sql.append("    CONSTRAINT recorded_range CHECK (superseded_at IS NULL")
                .append(" OR superseded_at > recorded_at)\n");
        sql.append(");\n");

        sql.append("CREATE UNIQUE INDEX uq_").append(name)
                .append("_current ON ").append(name)
                .append(" (id) WHERE superseded_at IS NULL;\n");

        sql.append("ALTER TABLE ").append(name).append(" ADD CONSTRAINT ex_").append(name)
                .append("_valid_overlap EXCLUDE USING gist (\n");
        sql.append("    id WITH =,\n");
        sql.append("    tstzrange(valid_from, valid_to, '[)') WITH &&,\n");
        sql.append("    tstzrange(recorded_at, superseded_at, '[)') WITH &&\n");
        sql.append(");\n");

        sql.append("CREATE INDEX idx_").append(name)
                .append("_valid_time ON ").append(name)
                .append(" (valid_from, valid_to);\n");
        sql.append("CREATE INDEX idx_").append(name)
                .append("_recorded_time ON ").append(name)
                .append(" (recorded_at, superseded_at);\n");
        sql.append("CREATE INDEX idx_").append(name)
                .append("_bitemporal ON ").append(name)
                .append(" (valid_from, valid_to, recorded_at, superseded_at);\n");
        sql.append(asOfFunctions(name));
        return sql.toString();
    }

    private static String asOfFunctions(String name) {
        StringBuilder sql = new StringBuilder(512);
        sql.append("CREATE FUNCTION ").append(name).append("_as_of_valid")
                .append("(p_id UUID, p_time TIMESTAMPTZ) RETURNS SETOF ").append(name)
                .append(" AS 'SELECT * FROM ").append(name)
                .append(" WHERE id = $1 AND valid_from <= $2")
                .append(" AND (valid_to IS NULL OR valid_to > $2)'")
                .append(" LANGUAGE sql STABLE;\n");
        sql.append("CREATE FUNCTION ").append(name).append("_as_of_recorded")
                .append("(p_id UUID, p_time TIMESTAMPTZ) RETURNS SETOF ").append(name)
                .append(" AS 'SELECT * FROM ").append(name)
                .append(" WHERE id = $1 AND recorded_at <= $2")
                .append(" AND (superseded_at IS NULL OR superseded_at > $2)'")
                .append(" LANGUAGE sql STABLE;\n");
        sql.append("CREATE FUNCTION ").append(name).append("_as_of_bitemporal")
                .append("(p_id UUID, p_valid_time TIMESTAMPTZ, p_recorded_time TIMESTAMPTZ)")
                .append(" RETURNS SETOF ").append(name)
                .append(" AS 'SELECT * FROM ").append(name)
                .append(" WHERE id = $1 AND valid_from <= $2")
                .append(" AND (valid_to IS NULL OR valid_to > $2)")
                .append(" AND recorded_at <= $3")
                .append(" AND (superseded_at IS NULL OR superseded_at > $3)'")
                .append(" LANGUAGE sql STABLE;\n");
        return sql.toString();
    }
}
