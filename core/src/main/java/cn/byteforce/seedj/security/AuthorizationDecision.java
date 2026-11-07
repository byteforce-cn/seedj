package cn.byteforce.seedj.security;

import java.util.Objects;

/**
 * 授权决策：允许/拒绝、细分原因与命中的策略。
 *
 * <p>拒绝必须携带非空 {@code reasonCode}（如 {@code SOD-001}、 {@code ATTRIBUTE_MISMATCH}），调用方据此写审计并映射错误信封；
 * 不允许只返回一个布尔值。
 *
 * @param allowed 是否允许
 * @param reasonCode 细分原因码；允许时可为 {@code null}
 * @param reason 人类可读原因
 * @param policyId 命中的策略 ID；未知时为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record AuthorizationDecision(
        boolean allowed, String reasonCode, String reason, String policyId) {

    public AuthorizationDecision {
        if (!allowed && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("denied decision requires a reasonCode");
        }
    }

    /**
     * 允许。
     *
     * @param policyId 命中的策略 ID
     * @return 决策
     */
    public static AuthorizationDecision allow(String policyId) {
        return new AuthorizationDecision(true, null, "allowed", policyId);
    }

    /**
     * 拒绝。
     *
     * @param reasonCode 细分原因码
     * @param reason 原因
     * @param policyId 命中的策略 ID
     * @return 决策
     */
    public static AuthorizationDecision deny(String reasonCode, String reason, String policyId) {
        Objects.requireNonNull(reason, "reason");
        return new AuthorizationDecision(false, reasonCode, reason, policyId);
    }
}
