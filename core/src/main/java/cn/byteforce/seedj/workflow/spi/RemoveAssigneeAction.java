package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 减签；只能移除 {@code PENDING} assignment。 */
public record RemoveAssigneeAction(String actionId, TaskId taskId, String operator,
                                   List<String> removedAssigneeIds,
                                   String reason, Instant occurredAt) implements WorkflowAction {
    public RemoveAssigneeAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        removedAssigneeIds = removedAssigneeIds == null ? List.of() : List.copyOf(removedAssigneeIds);
        if (removedAssigneeIds.isEmpty())
            throw new IllegalArgumentException("removedAssigneeIds cannot be empty");
    }
}
