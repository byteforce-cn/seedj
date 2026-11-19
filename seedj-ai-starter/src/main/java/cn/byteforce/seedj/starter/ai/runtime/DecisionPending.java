package cn.byteforce.seedj.starter.ai.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 待审批决策的摘要：循环只需要 decision_id，其余字段给宿主展示。
 *
 * @param decisionId 决策标识（后续 grant 用）
 * @param status 状态线格式名，通常是 {@code pending_approval}
 * @param deadline 审批截止时间；响应未给时为空
 * @param instanceId 审批实例；响应未给时为空
 * @param taskId 审批任务；响应未给时为空
 * @author ByteForce
 * @since 1.0.0
 */
public record DecisionPending(
        UUID decisionId, String status, Instant deadline, String instanceId, String taskId) {

    public DecisionPending {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(status, "status");
    }
}
