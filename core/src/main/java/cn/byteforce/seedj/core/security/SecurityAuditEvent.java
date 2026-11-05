package cn.byteforce.seedj.core.security;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 安全审计事件：认证、授权、凭证变更的结果记录。
 *
 * <p>与 {@code cn.byteforce.seedj.audit} 的哈希链分工（见 {@code @Audited}）：
 * 已受理的状态变更进哈希链；<b>被拒绝</b>的操作走本通道，不进哈希链。</p>
 *
 * <p>{@code details} 不得包含密码、OTP 或密钥材料；调用方负责脱敏。</p>
 *
 * @param id           事件标识，由 {@code IdGenerator} 产生
 * @param tenantId     租户；空白回落 {@link Caller#DEFAULT_TENANT}
 * @param actorId      操作人主体标识；未认证时为空串
 * @param action       稳定动作名（如 {@code order.delete}）
 * @param resourceType 资源类型（如 {@code order}）
 * @param resourceId   资源标识；列表/批量操作可用空串
 * @param outcome      结果（{@code SUCCESS}/{@code FAILURE}/{@code DENIED}）
 * @param reason       原因，面向运维；不得回显凭证内容
 * @param occurredAt   发生时间
 * @param details      附加字段，调用方保证脱敏
 * @author ByteForce
 * @since 1.0.0
 */
public record SecurityAuditEvent(
        String id,
        String tenantId,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String reason,
        Instant occurredAt,
        Map<String, Object> details) {

    /** 结果常量：操作成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** 结果常量：认证/授权被拒绝。 */
    public static final String OUTCOME_DENIED = "DENIED";

    /** 结果常量：失败但未被拒绝（如存储不可用）。 */
    public static final String OUTCOME_FAILURE = "FAILURE";

    public SecurityAuditEvent {
        id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        tenantId = tenantId == null || tenantId.isBlank() ? Caller.DEFAULT_TENANT : tenantId;
        actorId = actorId == null ? "" : actorId;
        action = action == null ? "" : action;
        resourceType = resourceType == null ? "" : resourceType;
        resourceId = resourceId == null ? "" : resourceId;
        outcome = outcome == null ? "UNKNOWN" : outcome;
        reason = reason == null ? "" : reason;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** 最小构造：默认租户、当前时间、无附加字段。 */
    public static SecurityAuditEvent of(String id, String actorId, String action,
                                        String resourceType, String resourceId,
                                        String outcome, String reason) {
        return new SecurityAuditEvent(id, Caller.DEFAULT_TENANT, actorId, action, resourceType,
                resourceId, outcome, reason, Instant.now(), Map.of());
    }

    /** 以某个调用方为主体构造；{@code caller} 为 null 时主体为空串。 */
    public static SecurityAuditEvent of(String id, Caller caller, String action,
                                        String resourceType, String resourceId,
                                        String outcome, String reason) {
        return new SecurityAuditEvent(id,
                caller == null ? Caller.DEFAULT_TENANT : caller.tenantId(),
                caller == null ? "" : caller.id(),
                action, resourceType, resourceId, outcome, reason, Instant.now(), Map.of());
    }
}
