package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工作流定义：不可变模板。实例启动时固定版本，运行中不切换。
 */
public record WorkflowDefinition(
        WorkflowDefinitionId id, WorkflowDefinitionVersion version,
        List<StepDefinition> steps, Duration defaultStepTimeout,
        Duration workflowTimeout, Map<String, String> metadata
) {
    public WorkflowDefinition {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
