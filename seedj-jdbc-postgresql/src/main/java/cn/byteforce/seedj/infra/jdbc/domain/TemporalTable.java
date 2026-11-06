package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;

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
 * <p>{@link #from(ObjectSchema)} 是从业务 schema 派生的唯一入口：列类型、写入专用列、
 * 可过滤列与字段类型都来自同一份声明，避免手工表定义与 schema 漂移。旧的四参构造器保留给
 * 尚未迁移的装配与测试。</p>
 *
 * @param objectType       对象类型
 * @param tableName        表名
 * @param columns          领域列（列名 → SQL 类型，保持插入顺序）
 * @param writeOnlyColumns 写入专用列（读投影剔除）
 * @param filterableColumns 允许过滤/排序的列
 * @param fieldTypes       列名 → 业务字段类型（用于 JDBC 值转换）
 * @author ByteForce
 * @since 1.0.0
 */
public record TemporalTable(String objectType, String tableName, Map<String, String> columns,
                            Set<String> writeOnlyColumns, Set<String> filterableColumns,
                            Map<String, cn.byteforce.seedj.domain.FieldType> fieldTypes) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final Pattern TYPE = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_ ]*(\\([0-9]+(,[0-9]+)?\\))?");

    public TemporalTable {
        identifier(objectType, "objectType");
        identifier(tableName, "tableName");
        Objects.requireNonNull(columns, "columns");
        Map<String, String> copy = new LinkedHashMap<>();
        columns.forEach((name, type) -> {
            identifier(name, "column");
            if (ObjectSchema.META_FIELDS.contains(name)) {
                throw new IllegalArgumentException("column clashes with meta field: " + name);
            }
            Objects.requireNonNull(type, "column type");
            if (!TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("illegal column type: " + type);
            }
            copy.put(name, type);
        });
        columns = Collections.unmodifiableMap(copy);
        writeOnlyColumns = writeOnlyColumns == null ? Set.of() : Set.copyOf(writeOnlyColumns);
        for (String column : writeOnlyColumns) {
            if (!copy.containsKey(column)) {
                throw new IllegalArgumentException("writeOnly column is not declared: " + column);
            }
        }
        filterableColumns = filterableColumns == null
                ? Set.copyOf(copy.keySet()) : Set.copyOf(filterableColumns);
        for (String column : filterableColumns) {
            if (!copy.containsKey(column)) {
                throw new IllegalArgumentException("filterable column is not declared: " + column);
            }
        }
        fieldTypes = fieldTypes == null ? Map.of() : Map.copyOf(fieldTypes);
        for (String column : fieldTypes.keySet()) {
            if (!copy.containsKey(column)) {
                throw new IllegalArgumentException("field type column is not declared: " + column);
            }
        }
    }

    /**
     * 构造无写入专用列、全部列可过滤的映射（旧装配兼容）。
     *
     * @param objectType 对象类型
     * @param tableName  表名
     * @param columns    领域列
     */
    public TemporalTable(String objectType, String tableName, Map<String, String> columns) {
        this(objectType, tableName, columns, Set.of());
    }

    /**
     * 构造全默认映射（旧装配兼容）。
     *
     * @param objectType       对象类型
     * @param tableName        表名
     * @param columns          领域列
     * @param writeOnlyColumns 写入专用列
     */
    public TemporalTable(String objectType, String tableName, Map<String, String> columns,
                         Set<String> writeOnlyColumns) {
        this(objectType, tableName, columns, writeOnlyColumns, null, null);
    }

    /**
     * 从业务 schema 派生物理表。
     *
     * @param schema 对象 schema
     * @return 物理表映射
     */
    public static TemporalTable from(ObjectSchema schema) {
        Objects.requireNonNull(schema, "schema");
        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, cn.byteforce.seedj.domain.FieldType> types = new LinkedHashMap<>();
        Set<String> writeOnly = new java.util.LinkedHashSet<>();
        Set<String> filterable = new java.util.LinkedHashSet<>();
        for (FieldDefinition field : schema.fields()) {
            columns.put(field.name(), field.type().sqlType());
            types.put(field.name(), field.type());
            if (field.writeOnly()) {
                writeOnly.add(field.name());
            }
            if (field.filterable()) {
                filterable.add(field.name());
            }
        }
        return new TemporalTable(schema.objectType(), schema.tableName(), columns, writeOnly,
                filterable, types);
    }

    private static void identifier(String value, String role) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("illegal " + role + ": " + value);
        }
    }
}
