package cn.byteforce.seedj.workflow.spi;

import java.util.Map;
import java.util.Objects;

/** 启动请求。{@code definitionVersion == null} 表示使用该定义的最高版本。 */
public record StartWorkflowRequest(
        WorkflowInstanceId instanceId,
        WorkflowDefinitionId definitionId,
        WorkflowDefinitionVersion definitionVersion,
        String startedBy,
        Map<String, String> initialVariables) {
    public StartWorkflowRequest {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(definitionId, "definitionId cannot be null");
        Objects.requireNonNull(startedBy, "startedBy cannot be null");
        initialVariables = initialVariables == null ? Map.of() : Map.copyOf(initialVariables);
    }
}
