package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 人工任务快照。 */
public record HumanTask(
        TaskId id, WorkflowInstanceId instanceId, String stepName,
        HumanTaskKind kind, HumanTaskStatus status,
        List<TaskAssignment> assignments, Map<String, String> formData,
        TaskId predecessorTaskId, TaskId successorTaskId, String spawnedByActionId,
        Instant createdAt, Instant updatedAt, Instant completedAt, Instant dueAt,
        Map<String, String> metadata
) {
    public HumanTask {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(stepName, "stepName cannot be null");
        Objects.requireNonNull(kind, "kind cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        formData = formData == null ? Map.of() : Map.copyOf(formData);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 当前仍可处理的参与者（PENDING assignment）。 */
    public List<ParticipantSpec> pendingAssignees() {
        return assignments.stream()
                .filter(a -> a.status() == AssignmentStatus.PENDING)
                .map(TaskAssignment::participant).toList();
    }

    /** 前加签期间被冻结的参与者（SUSPENDED assignment）。 */
    public List<ParticipantSpec> suspendedAssignees() {
        return assignments.stream()
                .filter(a -> a.status() == AssignmentStatus.SUSPENDED)
                .map(TaskAssignment::participant).toList();
    }

    /** 已产生的审批记录（COMPLETED assignment）；WITHDRAWN 任务下仅作历史审计。 */
    public List<ApproveRecord> approveRecords() {
        return assignments.stream()
                .filter(a -> a.status() == AssignmentStatus.COMPLETED)
                .map(TaskAssignment::record).toList();
    }
}
