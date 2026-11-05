package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 工作流实例标识；{@code start} 按此幂等。 */
public record WorkflowInstanceId(String value) {
    public WorkflowInstanceId {
        Objects.requireNonNull(value, "value cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("value cannot be blank");
    }
}
