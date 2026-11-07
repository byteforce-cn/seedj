package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.domain.event.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 交给消费者的事件信封：领域事件本体 + 投递元数据。
 *
 * <p>业务方通常只使用 {@link #event()}；其余字段用于幂等、排查与按 group 诊断。
 */
public record EventEnvelope<E extends DomainEvent>(
        E event,
        String eventId,
        String topic,
        String group,
        int attempt,
        Instant occurredAt,
        Map<String, String> headers) {

    public EventEnvelope {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
