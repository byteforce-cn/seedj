package cn.byteforce.seedj.core.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 请求事实快照：不可变、按声明 key 读取。
 *
 * <p>它不是安全上下文，也不携带认证结果；只在请求生命周期内由
 * {@link ScopeContextHolder} 暴露。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeContext {

    private final Map<String, Object> facts;

    private ScopeContext(Map<String, Object> facts) {
        this.facts = facts;
    }

    /**
     * 空快照。
     *
     * @return 空上下文
     */
    public static ScopeContext empty() {
        return new ScopeContext(Map.of());
    }

    /**
     * 从事实构建快照：复制为不可变 map，拒绝 null key/value。
     *
     * @param facts 事实
     * @return 快照
     */
    public static ScopeContext of(Map<String, Object> facts) {
        Objects.requireNonNull(facts, "facts");
        Map<String, Object> copy = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("scope fact key must not be blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("scope fact " + key + " must not be null");
            }
            copy.put(key, value);
        });
        return new ScopeContext(Collections.unmodifiableMap(copy));
    }

    /**
     * 按名读取原始值。
     *
     * @param key key 名
     * @return 值；不存在为空
     */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(facts.get(key));
    }

    /**
     * 按声明读取并校验类型。
     *
     * @param key 声明
     * @param <T> 值类型
     * @return 值；不存在为空
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(ScopeKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = facts.get(key.name());
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of((T) key.type().validate(key.name(), value));
    }

    /**
     * 全部事实（保持写入顺序）。
     *
     * @return 不可变映射
     */
    public Map<String, Object> facts() {
        return facts;
    }
}
