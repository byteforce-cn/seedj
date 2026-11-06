package cn.byteforce.seedj.domain;

/**
 * 字段打码扩展点：把原值转换为同类型值或字符串。
 *
 * <p>只在 {@code ReadGateway} 的投影阶段调用；没有 masker 的字段不允许“猜一个规则”，
 * {@code MASKED} 会退化为隐藏。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface Masker {

    /**
     * 打码。
     *
     * @param field 字段名
     * @param value 原值（可能为 {@code null}）
     * @return 打码后的值
     */
    Object mask(String field, Object value);
}
