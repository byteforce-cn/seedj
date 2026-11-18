package cn.byteforce.seedj.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批快照：不可逆动作执行前，人工审批系统给出的结论。
 *
 * <p>快照绑定 {@code argsHash}：审批的是“这组参数”，参数一变就必须重新审批。{@code instanceRevision}
 * 记录审批实例版本，用于识别“审批后被改过”的实例。已批准时 {@code approver} 与 {@code approvedAt} 必填， 否则审计无法回答“谁在什么时候批的”。
 *
 * @param instanceId 审批实例标识（外部工作流实例）
 * @param taskId 审批任务标识
 * @param status 审批状态
 * @param requestedAt 发起审批的时间
 * @param approver 批准人；{@code status == APPROVED} 时必填
 * @param approvedAt 批准时间；{@code status == APPROVED} 时必填
 * @param argsHash 被审批的参数指纹（{@code sha256:} 前缀），审批与执行必须逐字一致
 * @param instanceRevision 审批实例版本；{@code null} 表示外部系统未提供
 * @author ByteForce
 * @since 1.0.0
 */
public record ApprovalSnapshot(
        String instanceId,
        String taskId,
        ApprovalStatus status,
        Instant requestedAt,
        UUID approver,
        Instant approvedAt,
        String argsHash,
        Long instanceRevision) {

    /**
     * 校验必填项与“已批准必须有批准人/批准时间”的约束。
     *
     * @throws NullPointerException 必填项为 {@code null}
     * @throws IllegalArgumentException 字段为空白，或已批准但缺少批准人或批准时间
     */
    public ApprovalSnapshot {
        instanceId = requiredText(instanceId, "instanceId");
        taskId = requiredText(taskId, "taskId");
        status = Objects.requireNonNull(status, "status");
        argsHash = requiredText(argsHash, "argsHash");
        if (status == ApprovalStatus.APPROVED) {
            Objects.requireNonNull(approver, "approver");
            Objects.requireNonNull(approvedAt, "approvedAt");
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
