package cn.byteforce.seedj.domain;

import java.util.Objects;

/**
 * 默认打码实现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class Maskers {

    private Maskers() {
    }

    /**
     * 只保留末尾四位；长度不超过四位时全部隐藏。
     *
     * @return 打码器
     */
    public static Masker last4() {
        return (field, value) -> {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            if (text.length() <= 4) {
                return "****";
            }
            return "****" + text.substring(text.length() - 4);
        };
    }

    /**
     * 邮箱：保留 {@code @} 前后首字符，其余字符替换为 {@code *}。
     *
     * @return 打码器
     */
    public static Masker email() {
        return (field, value) -> {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            int at = text.indexOf('@');
            if (at <= 0 || at == text.length() - 1) {
                return "****";
            }
            String local = text.substring(0, at);
            String domain = text.substring(at + 1);
            return local.charAt(0) + "*".repeat(local.length() - 1) + "@"
                    + domain.charAt(0) + "*".repeat(domain.length() - 1);
        };
    }

    /**
     * 固定替换值。
     *
     * @param replacement 替换值
     * @return 打码器
     */
    public static Masker fixed(String replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return (field, value) -> value == null ? null : replacement;
    }
}
