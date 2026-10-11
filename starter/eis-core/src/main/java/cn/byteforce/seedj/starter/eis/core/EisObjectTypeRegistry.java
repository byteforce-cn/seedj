package cn.byteforce.seedj.starter.eis.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对象类型注册表：域服务启动时装载全部 {@link EisObjectType}（Bean）供运行时使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisObjectTypeRegistry {

    private final Map<String, EisObjectType> byName;

    /**
     * 构造注册表。
     *
     * @param types 对象类型集合（可空）
     */
    public EisObjectTypeRegistry(Collection<EisObjectType> types) {
        Map<String, EisObjectType> map = new LinkedHashMap<>();
        if (types != null) {
            for (EisObjectType type : types) {
                if (map.put(type.getObjectType(), type) != null) {
                    throw new IllegalArgumentException("重复注册对象类型: " + type.getObjectType());
                }
            }
        }
        this.byName = Collections.unmodifiableMap(map);
    }

    /**
     * 按名取对象类型。
     *
     * @param objectType 对象类型名
     * @return 对象类型；未注册为 {@code null}
     */
    public EisObjectType get(String objectType) {
        return byName.get(objectType);
    }

    /**
     * 按名取对象类型（必须存在）。
     *
     * @param objectType 对象类型名
     * @return 对象类型
     * @throws IllegalArgumentException 未注册时
     */
    public EisObjectType require(String objectType) {
        EisObjectType type = byName.get(objectType);
        if (type == null) {
            throw new IllegalArgumentException("未注册对象类型: " + objectType);
        }
        return type;
    }

    /**
     * 全部注册类型。
     *
     * @return 对象类型集合
     */
    public Collection<EisObjectType> all() {
        return byName.values();
    }
}
