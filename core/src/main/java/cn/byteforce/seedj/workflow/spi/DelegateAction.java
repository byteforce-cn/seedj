package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 委托：代理权授予。{@code delegateFor} 是相对时长，到期时间 = Provider 时钟 + delegateFor。 */
public record DelegateAction(
        String actionId, TaskId taskId, String fromAssignee,
        ParticipantSpec toAssignee, Duration delegateFor,
        String reason, Instant occurredAt
) implements WorkflowAction {
    public DelegateAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(fromAssignee, "fromAssignee cannot be null");
        Objects.requireNonNull(toAssignee, "toAssignee cannot be null");
        Objects.requireNonNull(delegateFor, "delegateFor cannot be null");
        if (delegateFor.isNegative() || delegateFor.isZero())
            throw new IllegalArgumentException("delegateFor must be positive");
    }
}
