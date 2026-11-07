package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;

import java.util.Map;
import java.util.Objects;

/**
 * 命令级授权策略：在目录执行命令之前决定“这个调用方能否执行这条命令”。
 *
 * <p>默认实现 {@link #permissions()} 以 {@link CommandDescriptor#permission()} 为准；权限是
 * 命令契约的一部分，不是 controller 的局部判断。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandPolicy {

    /**
     * 授权一次命令执行。
     *
     * @param descriptor 命令描述
     * @param caller     调用方
     * @param payload    原始载荷（供 ABAC 策略使用）
     * @throws ContractException 无权限（403）时抛出
     */
    void authorize(CommandDescriptor descriptor, Caller caller, Map<String, Object> payload);

    /**
     * 基于权限名的默认策略。
     *
     * <p>描述缺少权限时抛 {@link IllegalArgumentException}：这是装配错误，应在启动期暴露，
     * 而不是让一条命令变成“人人可执行”。</p>
     *
     * @return 权限策略
     * @deprecated 生产装配必须使用 {@link #decisionPoint(AuthorizationDecisionPoint, Clock)}；
     *             本方法只保留给隔离测试与旧测试夹具。
     */
    @Deprecated(forRemoval = false)
    static CommandPolicy permissions() {
        return (descriptor, caller, payload) -> {
            String permission = descriptor.permission();
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException(
                        "command " + descriptor.type() + " has no permission declared");
            }
            if (caller == null || !caller.hasPermission(permission)) {
                throw new ContractException(ContractErrorCode.FORBIDDEN,
                        "missing permission: " + permission);
            }
        };
    }

    /**
     * 走唯一授权决策点：命令权限是动作，对象类型是资源，资源 ID 在命令阶段未知。
     *
     * @param decisionPoint 授权决策点
     * @param clock         业务时点来源
     * @return 授权策略
     */
    static CommandPolicy decisionPoint(AuthorizationDecisionPoint decisionPoint, Clock clock) {
        return decisionPoint(decisionPoint, clock, null);
    }

    /**
     * 走唯一授权决策点，并把拒绝原因写入安全审计。
     *
     * <p>审计失败不得把 403 变成 500；{@code reason_code} 同时进入错误 details
     * 与审计条目，便于按 SoD/ABAC 规则号追踪。</p>
     *
     * @param decisionPoint 授权决策点
     * @param clock         业务时点来源
     * @param audit         安全审计出口；{@code null} 表示不记录
     * @return 授权策略
     */
    static CommandPolicy decisionPoint(AuthorizationDecisionPoint decisionPoint, Clock clock,
                                       SecurityAuditSink audit) {
        Objects.requireNonNull(decisionPoint, "decisionPoint");
        Objects.requireNonNull(clock, "clock");
        return (descriptor, caller, payload) -> {
            String permission = descriptor.permission();
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException(
                        "command " + descriptor.type() + " has no permission declared");
            }
            if (caller == null) {
                throw new ContractException(ContractErrorCode.UNAUTHENTICATED,
                        "caller is required");
            }
            java.time.Instant now = clock.now();
            AuthorizationDecision decision = decisionPoint.decide(new AuthorizationRequest(
                    AuthorizationSubject.from(caller), permission, descriptor.objectType(),
                    null, Map.of(), now, Map.of()));
            if (decision == null) {
                throw new IllegalStateException(
                        "authorization decision point returned null for " + permission);
            }
            if (!decision.allowed()) {
                recordDenied(audit, descriptor, caller, permission, decision, now);
                throw new ContractException(ContractErrorCode.FORBIDDEN, decision.reason(),
                        Map.of("reason_code", decision.reasonCode()), decision.reasonCode());
            }
        };
    }

    private static void recordDenied(SecurityAuditSink audit, CommandDescriptor descriptor,
                                     Caller caller, String permission,
                                     AuthorizationDecision decision, java.time.Instant now) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(new SecurityAuditEvent(java.util.UUID.randomUUID().toString(),
                    caller.tenantId(), caller.id(), permission, descriptor.objectType(), "",
                    SecurityAuditEvent.OUTCOME_DENIED, decision.reason(), now,
                    Map.of("reason_code", String.valueOf(decision.reasonCode()),
                            "command_type", descriptor.type())));
        } catch (RuntimeException ignored) {
            // 审计通道故障不得改变授权结论。
        }
    }

    /**
     * 不做授权检查；仅供测试与显式本地装配。
     *
     * @return 放行策略
     * @deprecated 使用 {@link AuthorizationDecisionPoint} 的 permit-all 实现，生产不得放行。
     */
    @Deprecated(forRemoval = false)
    static CommandPolicy allowAll() {
        return (descriptor, caller, payload) -> {};
    }
}
