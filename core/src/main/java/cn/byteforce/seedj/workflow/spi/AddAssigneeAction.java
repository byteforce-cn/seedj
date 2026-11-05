package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 加签；{@code position} 必须同时被任务规格与 Provider 能力允许。 */
public record AddAssigneeAction(String actionId, TaskId taskId, String operator,
                                List<ParticipantSpec> newAssignees,
                                AddAssigneePosition position,
                                String reason, Instant occurredAt) implements WorkflowAction {
    public AddAssigneeAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        newAssignees = newAssignees == null ? List.of() : List.copyOf(newAssignees);
        if (newAssignees.isEmpty())
            throw new IllegalArgumentException("newAssignees cannot be empty");
        position = position == null ? AddAssigneePosition.CURRENT : position;
    }
}
