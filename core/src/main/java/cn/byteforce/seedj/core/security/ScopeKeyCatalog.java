package cn.byteforce.seedj.core.security;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 冻结的请求事实 key 目录：同名 key 的类型必须一致。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeKeyCatalog {

    private final Map<String, ScopeKey<?>> byName;

    private ScopeKeyCatalog(Map<String, ScopeKey<?>> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    /**
     * 从声明构建目录。
     *
     * @param keys 声明（可为空）
     * @return 不可变目录
     */
    public static ScopeKeyCatalog of(Collection<ScopeKey<?>> keys) {
        Objects.requireNonNull(keys, "keys");
        Map<String, ScopeKey<?>> byName = new LinkedHashMap<>();
        for (ScopeKey<?> key : keys) {
            Objects.requireNonNull(key, "key");
            ScopeKey<?> previous = byName.putIfAbsent(key.name(), key);
            if (previous != null && previous.type() != key.type()) {
                throw new IllegalArgumentException(
                        "duplicate scope key with different type: "
                                + key.name()
                                + " ("
                                + previous.type()
                                + " vs "
                                + key.type()
                                + ")");
            }
        }
        return new ScopeKeyCatalog(byName);
    }

    /**
     * 按名取 key，未声明即失败。
     *
     * @param name key 名
     * @return 声明
     */
    public ScopeKey<?> require(String name) {
        ScopeKey<?> key = byName.get(name);
        if (key == null) {
            throw new IllegalArgumentException("unknown scope key: " + name);
        }
        return key;
    }

    /**
     * 校验一个声明已登记且类型一致。
     *
     * @param key 声明
     */
    public void require(ScopeKey<?> key) {
        Objects.requireNonNull(key, "key");
        ScopeKey<?> registered = require(key.name());
        if (registered.type() != key.type()) {
            throw new IllegalArgumentException(
                    "scope key "
                            + key.name()
                            + " is declared as "
                            + registered.type()
                            + ", got "
                            + key.type());
        }
    }

    /**
     * 已声明的 key 与类型。
     *
     * @return 不可变映射
     */
    public Map<String, ScopeKeyType> keys() {
        Map<String, ScopeKeyType> result = new LinkedHashMap<>();
        byName.forEach((name, key) -> result.put(name, key.type()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 全部声明，保持登记顺序。
     *
     * @return 不可变列表
     */
    public List<ScopeKey<?>> all() {
        return List.copyOf(byName.values());
    }
}
