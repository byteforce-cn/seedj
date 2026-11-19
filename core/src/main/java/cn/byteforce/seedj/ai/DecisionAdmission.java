package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 准入凭证：守卫已经校验过 token 并允许这一次命令进入执行侧。
 *
 * <p>它不是业务事实，也不撤销：命令回滚不删除 admission，由过期清理回收。数据库层用它做不可逆动作的兜底——
 * 「有合法 admission 行」才能写不可逆表，直连 SQL 没有 admission 一律拒绝。
 *
 * @param decisionId 决策标识（同时是命令幂等键）
 * @param tenantId 租户
 * @param commandType 命令类型
 * @param targets 目标对象（顺序敏感）
 * @param argsHash 参数指纹
 * @param actor 执行主体
 * @param expiresAt 过期时间（不超过 token 的 {@code exp}，且不超过部署上限）
 * @param admittedAt 准入时间
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionAdmission(
        UUID decisionId,
        UUID tenantId,
        String commandType,
        List<UUID> targets,
        String argsHash,
        UUID actor,
        Instant expiresAt,
        Instant admittedAt) {

    /**
     * 校验必填项、目标列表与时间关系。
     *
     * @throws NullPointerException 任一必填项为 {@code null}
     * @throws IllegalArgumentException 文本为空白、目标含 {@code null}，或 {@code expiresAt < admittedAt}
     */
    public DecisionAdmission {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        commandType = requiredText(commandType, "commandType");
        argsHash = requiredText(argsHash, "argsHash");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(admittedAt, "admittedAt");
        targets = targets == null ? List.of() : targets;
        for (UUID target : targets) {
            if (target == null) {
                throw new IllegalArgumentException("targets must not contain null");
            }
        }
        targets = List.copyOf(targets);
        if (expiresAt.isBefore(admittedAt)) {
            throw new IllegalArgumentException("expiresAt must not be before admittedAt");
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
