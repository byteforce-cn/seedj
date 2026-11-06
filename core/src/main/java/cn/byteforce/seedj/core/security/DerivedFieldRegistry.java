package cn.byteforce.seedj.core.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 按逻辑字段名索引的派生字段注册表。
 *
 * <p>业务代码只认识 schema 里的逻辑名（例如 {@code id_number_hash}），不直接注入多个
 * {@link DerivedField} bean——后者在存在多个派生字段时会产生注入歧义，也让命令代理与
 * 具体密钥实现耦合。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DerivedFieldRegistry {

    private final Map<String, DerivedField> byName;

    /**
     * 构造注册表。
     *
     * @param derivedFields 逻辑名 → 派生字段
     */
    public DerivedFieldRegistry(Map<String, DerivedField> derivedFields) {
        Objects.requireNonNull(derivedFields, "derivedFields");
        Map<String, DerivedField> copy = new LinkedHashMap<>();
        derivedFields.forEach((name, field) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("derived field name is required");
            }
            copy.put(name, Objects.requireNonNull(field, "derived field " + name));
        });
        this.byName = Map.copyOf(copy);
    }

    /**
     * 按逻辑名取派生字段，未注册即失败。
     *
     * @param name 逻辑字段名
     * @return 派生字段
     */
    public DerivedField require(String name) {
        DerivedField field = byName.get(name);
        if (field == null) {
            throw new IllegalArgumentException("unknown derived field: " + name
                    + " (known: " + byName.keySet() + ")");
        }
        return field;
    }

    /**
     * 已注册的逻辑名。
     *
     * @return 不可变集合
     */
    public java.util.Set<String> names() {
        return byName.keySet();
    }
}
