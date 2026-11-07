package cn.byteforce.seedj.infra.jdbc.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对象类型到物理表的注册表。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalTables {

    private final Map<String, TemporalTable> byType;

    /**
     * 构造注册表。
     *
     * @param tables 对象表定义
     */
    public TemporalTables(List<TemporalTable> tables) {
        Objects.requireNonNull(tables, "tables");
        Map<String, TemporalTable> map = new LinkedHashMap<>();
        for (TemporalTable table : tables) {
            if (map.putIfAbsent(table.objectType(), table) != null) {
                throw new IllegalArgumentException("duplicate objectType: " + table.objectType());
            }
        }
        this.byType = Map.copyOf(map);
    }

    /**
     * 按对象类型取表定义。
     *
     * @param objectType 对象类型
     * @return 表定义
     */
    public TemporalTable require(String objectType) {
        TemporalTable table = byType.get(objectType);
        if (table == null) {
            throw new IllegalArgumentException(
                    "unknown object type: " + objectType + " (known: " + byType.keySet() + ")");
        }
        return table;
    }

    /**
     * 是否没有任何对象表。
     *
     * @return 空注册表为 {@code true}
     */
    public boolean isEmpty() {
        return byType.isEmpty();
    }

    /**
     * 读投影应剔除的写入专用列。
     *
     * @param objectType 对象类型
     * @return 列名集合；未知类型返回空集
     */
    public java.util.Set<String> writeOnlyColumns(String objectType) {
        TemporalTable table = byType.get(objectType);
        return table == null ? java.util.Set.of() : table.writeOnlyColumns();
    }
}
