package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 决策 token 的 claim 值：守卫校验所需的最小事实集合。
 *
 * <p>本类型只存值，不存签名或 JWS 对象——{@code core} 不依赖 token 库，签发与验签由 starter 侧完成。 token 只描述“被批准的这一次执行”：
 * {@code command} + {@code argsHash} 固定命令与参数，{@code actor}/{@code onBehalfOf}/{@code tenant}
 * 固定主体，{@code issuedAt}/{@code expiresAt}/{@code audience} 固定有效期与受众。
 *
 * <p>{@link #approval()} 与 {@link ActionClass#requiresApproval()} 的对应关系在这里就强制：需要审批的动作分类 必须带
 * <b>已批准</b>的审批快照，不需要的必须不带。只校验“快照非空”不够：{@code rejected}/{@code pending} 的审批
 * 会签出一张“无法拒绝”的 token，而守卫的错误码表里没有“审批未通过”这一项。
 *
 * @param v claim 版本，固定为 {@value #VERSION}
 * @param decisionId 决策标识
 * @param ledgerIntentId 账本意图记录标识（token 只能基于已落账的意图签发）
 * @param command 命令引用
 * @param argsHash 参数指纹
 * @param actor 执行主体
 * @param onBehalfOf 被代理用户
 * @param tenant 租户标识
 * @param actionClass 动作分类
 * @param policy 策略判定引用；{@code null} 表示 T0 未挂策略层
 * @param approval 审批快照；{@code actionClass.requiresApproval()} 时必须为已批准，否则必须为 {@code null}
 * @param issuedAt 签发时间
 * @param expiresAt 过期时间
 * @param audience 受众（服务名）
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionTokenClaims(
        int v,
        UUID decisionId,
        UUID ledgerIntentId,
        CommandRef command,
        String argsHash,
        UUID actor,
        UUID onBehalfOf,
        String tenant,
        ActionClass actionClass,
        PolicyRef policy,
        ApprovalSnapshot approval,
        Instant issuedAt,
        Instant expiresAt,
        String audience) {

    /** 当前 claim 版本。 */
    public static final int VERSION = 1;

    /**
     * 校验版本、必填项与“动作分类 ↔ 审批快照”的对应关系。
     *
     * @throws IllegalArgumentException {@code v} 不是 {@value #VERSION}、文本为空白、审批状态不是 {@code approved}，
     *     或审批快照与动作分类不匹配
     * @throws NullPointerException 必填项为 {@code null}
     */
    public DecisionTokenClaims {
        if (v != VERSION) {
            throw new IllegalArgumentException("unsupported claim version: " + v);
        }
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(ledgerIntentId, "ledgerIntentId");
        command = Objects.requireNonNull(command, "command");
        argsHash = requiredText(argsHash, "argsHash");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
        tenant = requiredText(tenant, "tenant");
        actionClass = Objects.requireNonNull(actionClass, "actionClass");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        audience = requiredText(audience, "audience");
        if (actionClass.requiresApproval()) {
            approval = Objects.requireNonNull(approval, "approval");
            if (approval.status() != ApprovalStatus.APPROVED) {
                throw new IllegalArgumentException(
                        "approval.status must be approved for approval-requiring actions, was "
                                + approval.status().wire());
            }
        } else if (approval != null) {
            throw new IllegalArgumentException(
                    "approval is only allowed for approval-requiring actions");
        }
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
