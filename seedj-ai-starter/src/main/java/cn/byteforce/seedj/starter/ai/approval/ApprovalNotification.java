package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.ai.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批通知：发给人看的摘要，不含 token、不含载荷原文。
 *
 * @param decisionId 决策标识
 * @param tenantId 租户
 * @param commandType 命令类型
 * @param argsHash 参数指纹
 * @param rationale 决策理由
 * @param approvers 审批人
 * @param requestedAt 发起时间
 * @param deadline 审批时限
 * @param status 当前状态
 * @author ByteForce
 * @since 1.0.0
 */
public record ApprovalNotification(
        UUID decisionId,
        UUID tenantId,
        String commandType,
        String argsHash,
        String rationale,
        List<UUID> approvers,
        Instant requestedAt,
        Instant deadline,
        ApprovalStatus status) {

    public ApprovalNotification {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(argsHash, "argsHash");
        Objects.requireNonNull(approvers, "approvers");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(status, "status");
        approvers = List.copyOf(approvers);
    }
}
