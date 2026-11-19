package cn.byteforce.seedj.starter.ai.effect;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次外部调用的输入：{@code decisionId} 是必须传给外部系统的幂等键。
 *
 * @param decisionId 决策标识（外部幂等键）
 * @param tenantId 租户
 * @param actor 执行主体
 * @param commandType 命令类型
 * @param argsHash 参数指纹
 * @param payload 调用参数（外部系统可见；不进账本）
 * @author ByteForce
 * @since 1.0.0
 */
public record ExternalEffectCall(
        UUID decisionId,
        UUID tenantId,
        UUID actor,
        String commandType,
        String argsHash,
        Map<String, Object> payload) {

    public ExternalEffectCall {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(argsHash, "argsHash");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
