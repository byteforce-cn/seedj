package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/** 转办：所有权转移；原 assignment 变 {@code TRANSFERRED}，不携带 record。 */
public record TransferAction(
        String actionId,
        TaskId taskId,
        String fromAssignee,
        ParticipantSpec toAssignee,
        String reason,
        Instant occurredAt)
        implements WorkflowAction {
    public TransferAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(fromAssignee, "fromAssignee cannot be null");
        Objects.requireNonNull(toAssignee, "toAssignee cannot be null");
    }
}
