package cn.byteforce.seedj.ai;

import java.util.Objects;

/**
 * 一条支撑决策的证据：为什么这么判断。
 *
 * <p>证据只记录可回溯的引用与摘要，不复制原文——原文留在来源系统，账本存副本会带来数据驻留与脱敏问题。 三个字段都可以缺，但 {@code kind}
 * 必须说明证据类别，否则这条证据无法被复核。
 *
 * @param kind 证据类别（如 {@code message} / {@code policy} / {@code document}）
 * @param ref 来源引用（URI 或系统内标识）；{@code null} 表示无引用
 * @param digest 来源内容的摘要；{@code null} 表示未计算
 * @author ByteForce
 * @since 1.0.0
 */
public record Evidence(String kind, String ref, String digest) {

    /**
     * 校验证据类别。
     *
     * @throws NullPointerException {@code kind} 为 {@code null}
     * @throws IllegalArgumentException {@code kind} 为空白
     */
    public Evidence {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
