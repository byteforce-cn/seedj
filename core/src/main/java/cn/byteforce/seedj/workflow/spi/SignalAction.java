package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 唤醒 {@code WAIT_SIGNAL} 步骤；{@code signalName} 必须等于步骤声明的 {@code waitSignalName}。 */
public record SignalAction(
        String actionId, String signalName, Map<String, String> payload, Instant occurredAt)
        implements WorkflowAction {
    public SignalAction {
        Objects.requireNonNull(actionId, "actionId cannot be null");
        Objects.requireNonNull(signalName, "signalName cannot be null");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
