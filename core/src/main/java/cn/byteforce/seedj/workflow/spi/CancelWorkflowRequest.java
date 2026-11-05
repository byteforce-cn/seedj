package cn.byteforce.seedj.workflow.spi;

import java.util.Objects;

/** 取消请求；{@code compensate == true} 时触发 Saga 逆序补偿。{@code terminate} 不补偿。 */
public record CancelWorkflowRequest(WorkflowInstanceId instanceId, String reason,
                                    boolean compensate) {
    public CancelWorkflowRequest {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
    }
}
