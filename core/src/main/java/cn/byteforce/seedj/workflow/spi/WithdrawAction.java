package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/** 任务级撤回：仅原审批人，且下游未完成。触发撤回的 assignment 保持 COMPLETED。 */
public record WithdrawAction(
        String actionId,
        TaskId taskId,
        String operator,
        String targetStep,
        String reason,
        Instant occurredAt)
        implements WorkflowAction {
    public WithdrawAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(operator, "operator cannot be null");
        Objects.requireNonNull(targetStep, "targetStep cannot be null");
    }
}
