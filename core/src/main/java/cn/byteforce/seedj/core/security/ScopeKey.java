package cn.byteforce.seedj.core.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 声明式请求事实 key：点分小写名 + 类型。
 *
 * <p>同名 key 的类型由 {@link ScopeKeyCatalog} 在启动期冻结，策略编译器只接受已声明 key。</p>
 *
 * @param name key 名（如 {@code org.units}）
 * @param type 值类型
 * @param <T>  Java 类型（仅用于调用方类型推断）
 * @author ByteForce
 * @since 1.0.0
 */
public record ScopeKey<T>(String name, ScopeKeyType type) {

    private static final Pattern NAME =
            Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    public ScopeKey {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal scope key name: " + name);
        }
        type = Objects.requireNonNull(type, "type");
    }
}
