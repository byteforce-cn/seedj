package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 动态参与者解析上下文。字段固定，Provider 不得要求额外输入。 */
public record ParticipantResolutionContext(
        String expression,
        WorkflowInstanceId instanceId,
        WorkflowDefinitionId definitionId,
        WorkflowDefinitionVersion definitionVersion,
        String stepName,
        Map<String, String> variables,
        Instant resolvedAt) {
    public ParticipantResolutionContext {
        Objects.requireNonNull(expression, "expression cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(definitionId, "definitionId cannot be null");
        Objects.requireNonNull(definitionVersion, "definitionVersion cannot be null");
        Objects.requireNonNull(stepName, "stepName cannot be null");
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
