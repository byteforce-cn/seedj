package cn.byteforce.seedj.starter.ai.effect;

import java.util.Objects;

/**
 * 外部系统确认的结果。
 *
 * @param externalRef 外部回执（对账与审计的关联键）
 * @author ByteForce
 * @since 1.0.0
 */
public record ExternalEffectResult(String externalRef) {

    public ExternalEffectResult {
        Objects.requireNonNull(externalRef, "externalRef");
        if (externalRef.isBlank()) {
            throw new IllegalArgumentException("externalRef must not be blank");
        }
    }
}
