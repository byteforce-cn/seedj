package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 动作请求。 */
public record ActionRequest(WorkflowInstanceId instanceId, WorkflowAction action) {
    public ActionRequest {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
    }
}
