package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 工作流定义的稳定标识：{@code namespace + name}，版本单独建模。 */
public record WorkflowDefinitionId(String namespace, String name) {
    public WorkflowDefinitionId {
        Objects.requireNonNull(namespace, "namespace cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
    }
}
