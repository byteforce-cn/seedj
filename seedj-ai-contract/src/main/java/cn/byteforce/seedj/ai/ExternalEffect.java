package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次外部副作用的两段事实：{@code decisionId} 是外部系统的幂等键，状态只前进不回退。
 *
 * <p>效果表不是业务链：它只回答「这次外部调用有没有固化、有没有确认」，账本 outcome 仍由调用方上报或对账补。 记录体不再携带 payload 原文，参数指纹是 {@code
 * argsHash}。
 *
 * @param decisionId 决策标识（外部幂等键）
 * @param tenantId 租户
 * @param commandType 命令类型
 * @param argsHash 参数指纹
 * @param state 状态
 * @param externalRef 外部系统回执；{@code CONFIRMED} 时必填
 * @param errorCode 失败原因；{@code FAILED} 时必填
 * @param preparedAt 固化时间
 * @param completedAt 完成时间；{@code CONFIRMED}/{@code FAILED} 时必填
 * @author ByteForce
 * @since 1.0.0
 */
public record ExternalEffect(
        UUID decisionId,
        UUID tenantId,
        String commandType,
        String argsHash,
        ExternalEffectState state,
        String externalRef,
        String errorCode,
        Instant preparedAt,
        Instant completedAt) {

    public ExternalEffect {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        commandType = text(commandType, "commandType");
        argsHash = text(argsHash, "argsHash");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (state == ExternalEffectState.CONFIRMED) {
            externalRef = text(externalRef, "externalRef");
            Objects.requireNonNull(completedAt, "completedAt");
        } else if (state == ExternalEffectState.FAILED) {
            errorCode = text(errorCode, "errorCode");
            Objects.requireNonNull(completedAt, "completedAt");
        } else if (externalRef != null || errorCode != null || completedAt != null) {
            throw new IllegalArgumentException(
                    "PREPARED/UNKNOWN must not carry externalRef/errorCode/completedAt");
        }
    }

    /**
     * 固化一次外部调用意图。
     *
     * @param decisionId 决策标识
     * @param tenantId 租户
     * @param commandType 命令类型
     * @param argsHash 参数指纹
     * @param now 固化时间
     * @return PREPARED 效果
     */
    public static ExternalEffect prepared(
            UUID decisionId, UUID tenantId, String commandType, String argsHash, Instant now) {
        return new ExternalEffect(
                decisionId,
                tenantId,
                commandType,
                argsHash,
                ExternalEffectState.PREPARED,
                null,
                null,
                now,
                null);
    }

    /**
     * 外部系统已确认。
     *
     * @param reference 外部回执
     * @param now 完成时间
     * @return CONFIRMED 效果
     */
    public ExternalEffect confirmed(String reference, Instant now) {
        return new ExternalEffect(
                decisionId,
                tenantId,
                commandType,
                argsHash,
                ExternalEffectState.CONFIRMED,
                reference,
                null,
                preparedAt,
                now);
    }

    /**
     * 外部系统明确失败。
     *
     * @param code 错误码
     * @param now 完成时间
     * @return FAILED 效果
     */
    public ExternalEffect failed(String code, Instant now) {
        return new ExternalEffect(
                decisionId,
                tenantId,
                commandType,
                argsHash,
                ExternalEffectState.FAILED,
                null,
                code,
                preparedAt,
                now);
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
