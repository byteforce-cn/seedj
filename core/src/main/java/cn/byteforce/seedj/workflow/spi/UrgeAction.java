package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * 催办：只记录 + 通知，不改状态机。
 *
 * <p>终态任务抛 {@link HumanTaskStateConflictException}，且不写历史事件；
 * 前加签期间原任务（assignment 为 SUSPENDED）允许催办。
 */
public record UrgeAction(String actionId, TaskId taskId, String operator,
                         String message, Instant occurredAt) implements WorkflowAction {
    public UrgeAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(operator, "operator cannot be null");
    }
}
