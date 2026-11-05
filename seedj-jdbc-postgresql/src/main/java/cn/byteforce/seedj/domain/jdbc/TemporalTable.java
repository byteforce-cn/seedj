package cn.byteforce.seedj.domain.jdbc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一张业务对象表的物理映射：对象类型 → 表名 + 领域列。
 *
 * <p>表名与列名只允许小写标识符，类型只允许常见的 SQL 类型写法；
 * 元字段名（id/version/valid_* 等）不得作为领域列重复声明。</p>
 *
 * @param objectType 对象类型
 * @param tableName  表名
 * @param columns    领域列（列名 → SQL 类型，保持插入顺序）
 * @author ByteForce
 * @since 1.0.0
 */
public record TemporalTable(String objectType, String tableName, Map<String, String> columns) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Pattern TYPE = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_ ]*(\\([0-9]+(,[0-9]+)?\\))?");

    private static final Set<String> META_COLUMNS = Set.of(
            "id", "version", "valid_from", "valid_to", "recorded_at", "superseded_at",
            "created_by", "created_at", "updated_by", "updated_at");

    public TemporalTable {
        identifier(objectType, "objectType");
        identifier(tableName, "tableName");
        Objects.requireNonNull(columns, "columns");
        Map<String, String> copy = new LinkedHashMap<>();
        columns.forEach((name, type) -> {
            identifier(name, "column");
            if (META_COLUMNS.contains(name)) {
                throw new IllegalArgumentException("column clashes with meta field: " + name);
            }
            Objects.requireNonNull(type, "column type");
            if (!TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("illegal column type: " + type);
            }
            copy.put(name, type);
        });
        columns = Collections.unmodifiableMap(copy);
    }

    private static void identifier(String value, String role) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("illegal " + role + ": " + value);
        }
    }
}
