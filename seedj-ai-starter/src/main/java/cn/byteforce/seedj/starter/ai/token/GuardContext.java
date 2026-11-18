package cn.byteforce.seedj.starter.ai.token;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行侧看到的请求侧事实：校验 DecisionToken 时用来与 claim 逐项比对。
 *
 * <p><b>这些是请求侧事实，不是可信输入</b>：{@code actor} 是已认证调用主体、{@code tenantId} 是命令上下文的租户、
 * {@code payload}/{@code targets}/{@code expectedVersion} 是这次调用真正要执行的内容。校验方只用它们与 token 比对，
 * 不做任何授权决策——「这次执行该不该发生」由签发方（决策面）在签 token 前判过，执行侧只回答「凭据与本次请求是否
 * 一致」。
 *
 * <p>{@code payload} 是<b>未脱敏原文</b>：{@code args_hash} 按它重算，脱敏只影响账本里可读的 {@code args}。
 * 允许 null 值（与命令载荷的 JSON 语义一致），键序保留但不参与哈希。
 *
 * @param actor 已认证调用主体（agent principal）
 * @param tenantId 命令上下文的租户；与 token 的 {@code tenant}（字符串）按 {@code toString()} 比对
 * @param commandType 实际命令类型，必须与工具注册表登记的类型一致
 * @param payload 未脱敏命令载荷
 * @param targets 实际目标对象；{@code null} 视为空列表，顺序参与比对
 * @param expectedVersion 实际期望版本；{@code null} 表示调用方未声明版本
 * @param idempotencyKey 本次调用的幂等键，必须等于 token 的 {@code decision_id}
 * @param audience 本服务名（受众）
 * @author ByteForce
 * @since 1.0.0
 */
public record GuardContext(
        UUID actor,
        UUID tenantId,
        String commandType,
        Map<String, Object> payload,
        List<UUID> targets,
        Long expectedVersion,
        String idempotencyKey,
        String audience) {

    /**
     * 校验必填项并归一化集合组件。
     *
     * @throws NullPointerException {@code actor}/{@code tenantId}/{@code payload}/{@code audience} 为 {@code
     *     null}
     * @throws IllegalArgumentException {@code commandType} 或 {@code audience} 为空白
     */
    public GuardContext {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(tenantId, "tenantId");
        commandType = requiredText(commandType, "commandType");
        Objects.requireNonNull(payload, "payload");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        targets = targets == null ? List.of() : List.copyOf(targets);
        audience = requiredText(audience, "audience");
    }

    private static String requiredText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
