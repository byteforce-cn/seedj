package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 实例快照；调用方只读，不解析内部存储。 */
public record WorkflowInstance(
        WorkflowInstanceId id,
        WorkflowDefinitionId definitionId,
        WorkflowDefinitionVersion definitionVersion,
        WorkflowStatus status,
        String currentStep,
        List<TaskId> pendingTaskIds,
        String startedBy,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt,
        Map<String, String> variables,
        WorkflowFailure failure) {
    public WorkflowInstance {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(definitionId, "definitionId cannot be null");
        Objects.requireNonNull(definitionVersion, "definitionVersion cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        pendingTaskIds = pendingTaskIds == null ? List.of() : List.copyOf(pendingTaskIds);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
