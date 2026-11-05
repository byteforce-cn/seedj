package cn.byteforce.seedj.core;


import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link CodeEnum} 枚举工具：按 code 查找、必填查找与不可变索引。
 *
 * <p>工具类为无状态静态方法，供 codegen 生成的 Codec 与业务代码复用。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CodeEnums {

    private CodeEnums() {
    }

    /**
     * 按 code 查找枚举，找不到返回 {@code null}。
     *
     * @param enumClass 枚举类型
     * @param code      业务 code；为 {@code null} 时返回 {@code null}
     * @param <E>       枚举类型
     * @param <C>       code 类型
     * @return 命中的枚举；未命中返回 {@code null}
     */
    public static <E extends Enum<E> & CodeEnum<C>, C> E of(Class<E> enumClass, C code) {
        if (code == null) return null;
        return Arrays.stream(enumClass.getEnumConstants())
                .filter(e -> code.equals(e.getCode()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按 code 查找枚举，找不到抛 {@link IllegalArgumentException}。
     *
     * @param enumClass 枚举类型
     * @param code      业务 code
     * @param <E>       枚举类型
     * @param <C>       code 类型
     * @return 命中的枚举，必不为 {@code null}
     * @throws IllegalArgumentException 未命中任何枚举时抛出
     */
    public static <E extends Enum<E> & CodeEnum<C>, C> E ofRequired(Class<E> enumClass, C code) {
        E e = of(enumClass, code);
        if (e == null) {
            throw new IllegalArgumentException(
                    "无效的枚举 code: " + code + ", 类型: " + enumClass.getSimpleName());
        }
        return e;
    }

    /**
     * 构建 code → 枚举 的不可变索引。
     *
     * @param enumClass 枚举类型
     * @param <E>       枚举类型
     * @param <C>       code 类型
     * @return 不可变 Map
     */
    public static <E extends Enum<E> & CodeEnum<C>, C> Map<C, E> toMap(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .collect(Collectors.toUnmodifiableMap(CodeEnum::getCode, Function.identity()));
    }
}