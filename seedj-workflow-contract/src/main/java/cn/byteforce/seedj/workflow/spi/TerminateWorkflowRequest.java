package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 终止请求；不补偿。 */
public record TerminateWorkflowRequest(WorkflowInstanceId instanceId, String reason) {
    public TerminateWorkflowRequest {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
    }
}
