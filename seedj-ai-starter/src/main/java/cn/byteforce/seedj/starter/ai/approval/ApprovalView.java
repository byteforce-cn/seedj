package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.ai.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批视图：只读投影，不回传工作流内部状态。
 *
 * @param decisionId 决策标识
 * @param status 最新审批状态
 * @param instanceId 工作流实例标识
 * @param taskId 审批任务标识
 * @param requestedAt 发起时间
 * @param deadline 审批时限
 * @param approver 批准人；未批准时为空
 * @param approvedAt 批准时间；未批准时为空
 * @param argsHash 被审批的参数指纹
 * @param approvers 配置的审批人
 * @author ByteForce
 * @since 1.0.0
 */
public record ApprovalView(
        UUID decisionId,
        ApprovalStatus status,
        String instanceId,
        String taskId,
        Instant requestedAt,
        Instant deadline,
        UUID approver,
        Instant approvedAt,
        String argsHash,
        List<UUID> approvers) {

    public ApprovalView {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(argsHash, "argsHash");
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
    }
}
