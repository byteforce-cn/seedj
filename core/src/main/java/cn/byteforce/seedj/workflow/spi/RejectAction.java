package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Objects;

/** 驳回；{@code targetStep} 必须落在任务的 {@code rejectTargets} 白名单内。 */
public record RejectAction(String actionId, TaskId taskId, String assignee,
                           String targetStep, String comment,
                           Instant occurredAt) implements WorkflowAction {
    public RejectAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(assignee, "assignee cannot be null");
        Objects.requireNonNull(targetStep, "targetStep cannot be null");
    }
}
