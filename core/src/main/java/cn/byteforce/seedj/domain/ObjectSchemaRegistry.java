package cn.byteforce.seedj.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 对象类型到 {@link ObjectSchema} 的注册表。
 *
 * <p>放在 core 而不是 JDBC 模块：内存实现、HTTP 查询面与装配层都要读 schema，但它们不应 依赖 JDBC 适配器。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ObjectSchemaRegistry {

    private final Map<String, ObjectSchema> byType;

    /**
     * 构造注册表。
     *
     * @param schemas 对象 schema
     */
    public ObjectSchemaRegistry(List<ObjectSchema> schemas) {
        Objects.requireNonNull(schemas, "schemas");
        Map<String, ObjectSchema> map = new LinkedHashMap<>();
        for (ObjectSchema schema : schemas) {
            Objects.requireNonNull(schema, "schema");
            if (map.putIfAbsent(schema.objectType(), schema) != null) {
                throw new IllegalArgumentException("duplicate objectType: " + schema.objectType());
            }
        }
        this.byType = Map.copyOf(map);
    }

    /**
     * 空注册表。
     *
     * @return 不含任何 schema 的注册表
     */
    public static ObjectSchemaRegistry empty() {
        return new ObjectSchemaRegistry(List.of());
    }

    /**
     * 按对象类型取 schema，未注册即失败。
     *
     * @param objectType 对象类型
     * @return schema
     */
    public ObjectSchema require(String objectType) {
        ObjectSchema schema = byType.get(objectType);
        if (schema == null) {
            throw new IllegalArgumentException(
                    "unknown object type: " + objectType + " (known: " + byType.keySet() + ")");
        }
        return schema;
    }

    /**
     * 按对象类型取 schema。
     *
     * @param objectType 对象类型
     * @return schema；未注册为空
     */
    public Optional<ObjectSchema> find(String objectType) {
        return Optional.ofNullable(objectType == null ? null : byType.get(objectType));
    }

    /**
     * 全部 schema，顺序与装配顺序一致。
     *
     * @return 不可变列表
     */
    public List<ObjectSchema> all() {
        return List.copyOf(byType.values());
    }

    /**
     * 已注册的对象类型。
     *
     * @return 不可变集合
     */
    public Set<String> types() {
        return byType.keySet();
    }
}
