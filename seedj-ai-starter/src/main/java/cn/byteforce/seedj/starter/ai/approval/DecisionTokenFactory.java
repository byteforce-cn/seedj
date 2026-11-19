package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.starter.ai.ledger.RecordedIntent;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DecisionToken claims 的唯一拼装点：同步签发（{@code DecisionService}）与审批后签发（{@code
 * ApprovalService}）共用，避免两处对 {@code approval} / {@code policy} / {@code audience} 的语义漂移。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DecisionTokenFactory {

    private final DecisionTokenIssuer issuer;

    /**
     * 绑定签发方。
     *
     * @param issuer 签发方
     */
    public DecisionTokenFactory(DecisionTokenIssuer issuer) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    /**
     * 签发一条已组装好的 claim。
     *
     * @param claims claim
     * @return JWS compact 串
     */
    public String sign(DecisionTokenClaims claims) {
        return issuer.sign(claims);
    }

    /**
     * 用内存里的意图构造 claim。
     *
     * @param intent 决策意图
     * @param ledgerIntentId 已落账的 intent 记录标识
     * @param approval 审批快照；非审批动作必须为 {@code null}
     * @param tenantId 租户
     * @param actor 执行主体
     * @param onBehalfOf 被代理用户
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @param audience 受众
     * @return claim
     */
    public static DecisionTokenClaims claims(
            DecisionIntent intent,
            UUID ledgerIntentId,
            ApprovalSnapshot approval,
            UUID tenantId,
            UUID actor,
            UUID onBehalfOf,
            Instant issuedAt,
            Instant expiresAt,
            String audience) {
        Objects.requireNonNull(intent, "intent");
        return new DecisionTokenClaims(
                DecisionTokenClaims.VERSION,
                intent.decisionId(),
                Objects.requireNonNull(ledgerIntentId, "ledgerIntentId"),
                intent.command(),
                intent.argsHash(),
                actor,
                onBehalfOf,
                tenantId.toString(),
                intent.actionClass(),
                intent.policy(),
                approval,
                issuedAt,
                expiresAt,
                audience);
    }

    /**
     * 用账本读回的意图构造 claim。
     *
     * @param intent 账本意图
     * @param approval 审批快照
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @param audience 受众
     * @return claim
     */
    public static DecisionTokenClaims claims(
            RecordedIntent intent,
            ApprovalSnapshot approval,
            Instant issuedAt,
            Instant expiresAt,
            String audience) {
        Objects.requireNonNull(intent, "intent");
        return new DecisionTokenClaims(
                DecisionTokenClaims.VERSION,
                intent.decisionId(),
                intent.recordId(),
                intent.command(),
                intent.argsHash(),
                intent.actor(),
                intent.onBehalfOf(),
                intent.tenantId().toString(),
                intent.actionClass(),
                intent.policy(),
                approval,
                issuedAt,
                expiresAt,
                audience);
    }
}
