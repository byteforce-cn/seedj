package cn.byteforce.seedj.workflow.spi;

import java.time.Instant;
import java.util.Map;

/** 历史事件；Provider 按 §1.5.10 的顺序持久化，{@code sequence} 由存储层保证。 */
public record WorkflowHistoryEvent(
        String eventId,
        String eventType,
        String stepName,
        String taskId,
        String actionId,
        Instant occurredAt,
        Map<String, String> attributes) {
    public WorkflowHistoryEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
