package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 同意。{@code assignee} 必须对应 PENDING assignment（或当前委托的代理人）。 */
public record ApproveAction(String actionId, TaskId taskId, String assignee,
                            String comment, Map<String, String> formData,
                            Instant occurredAt) implements WorkflowAction {
    public ApproveAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(assignee, "assignee cannot be null");
        formData = formData == null ? Map.of() : Map.copyOf(formData);
    }
}
