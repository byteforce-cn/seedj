package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 决策的执行结局：命令到底有没有发生、结果是什么。
 *
 * <p>{@link #status} 是唯一必填项：没有结局状态就无法判断外部副作用是否存在。{@code externalRef} 指向外部系统的
 * 回执，{@code auditEntryId} 指向审计链条目，{@code commandId} 指向命令记录——三者都存在时，一次执行可以同时从业务、
 * 审计、账本三条线复核。
 *
 * <p>工厂方法不填 {@code completedAt}：值类型不读时钟，落账时间以 {@link DecisionRecord#recordedAt()} 为准；
 * 调用方确有外部完成时间时用规范构造器传入。
 *
 * @param status 结局状态
 * @param externalRef 外部系统回执引用；{@code null} 表示无外部系统
 * @param auditEntryId 审计链条目标识；{@code null} 表示未写审计
 * @param commandId 命令记录标识；{@code null} 表示未签发命令
 * @param errorCode 失败时的业务错误码；{@code null} 表示无错误
 * @param completedAt 外部完成时间；{@code null} 表示未观测
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionEffect(
        EffectStatus status,
        String externalRef,
        String auditEntryId,
        UUID commandId,
        String errorCode,
        Instant completedAt) {

    /**
     * 校验结局状态。
     *
     * @throws NullPointerException {@code status} 为 {@code null}
     */
    public DecisionEffect {
        Objects.requireNonNull(status, "status");
    }

    /**
     * 执行成功。
     *
     * @param auditEntryId 审计链条目标识；{@code null} 表示未写审计
     * @param commandId 命令记录标识；{@code null} 表示未记录命令
     * @return {@link EffectStatus#OK} 的结局
     */
    public static DecisionEffect ok(String auditEntryId, UUID commandId) {
        return new DecisionEffect(EffectStatus.OK, null, auditEntryId, commandId, null, null);
    }

    /**
     * 该步骤不产生命令副作用。
     *
     * @return {@link EffectStatus#NOT_APPLICABLE} 的结局
     */
    public static DecisionEffect notApplicable() {
        return new DecisionEffect(EffectStatus.NOT_APPLICABLE, null, null, null, null, null);
    }

    /**
     * 命令在守卫处被拒绝，从未签发。
     *
     * @param errorCode 拒绝原因的错误码 wire 名
     * @return {@link EffectStatus#NOT_ISSUED} 的结局
     */
    public static DecisionEffect notIssued(String errorCode) {
        return new DecisionEffect(EffectStatus.NOT_ISSUED, null, null, null, errorCode, null);
    }

    /**
     * 命令已签发但被业务规则拒绝。
     *
     * @param errorCode 业务错误码 wire 名
     * @return {@link EffectStatus#REJECTED} 的结局
     */
    public static DecisionEffect rejected(String errorCode) {
        return new DecisionEffect(EffectStatus.REJECTED, null, null, null, errorCode, null);
    }

    /**
     * 结果未知：命令可能已送达外部系统，需要核对。
     *
     * @return {@link EffectStatus#UNKNOWN} 的结局
     */
    public static DecisionEffect unknown() {
        return new DecisionEffect(EffectStatus.UNKNOWN, null, null, null, null, null);
    }

    /**
     * 执行失败。
     *
     * @param errorCode 失败原因的错误码 wire 名
     * @return {@link EffectStatus#FAILED} 的结局
     */
    public static DecisionEffect failed(String errorCode) {
        return new DecisionEffect(EffectStatus.FAILED, null, null, null, errorCode, null);
    }
}
