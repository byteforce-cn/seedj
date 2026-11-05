package cn.byteforce.seedj.eventbus;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 一条待投递的事件消息。
 *
 * <p>{@code sequence} 刻意<b>不在</b>这里：分区内顺序由投递机制分配，
 * 业务方不需要（也无法）知道。
 *
 * <p>{@code eventId} 是幂等键：同一个 {@code eventId} 重复写入只会落一份。
 */
public record EventMessage(
        String eventId,
        String topic,
        String partitionKey,
        String eventType,
        int eventVersion,
        byte[] payload,
        Map<String, String> headers,
        Instant occurredAt) {

    public EventMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion >= 1");
        }
        payload = payload.clone();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * 带新 headers 的副本（record 不可变；追踪上下文注入走这里）。
     *
     * @param headers 新 headers；{@code null} 视为空
     * @return 新消息，其余字段不变
     */
    public EventMessage withHeaders(Map<String, String> headers) {
        return new EventMessage(eventId, topic, partitionKey, eventType, eventVersion, payload,
                headers, occurredAt);
    }

    /** 最简单的消息：eventId 由调用方的 {@link cn.byteforce.seedj.id.IdGenerator} 产生。 */
    public static EventMessage of(String eventId, String topic, String partitionKey, byte[] payload) {
        return new EventMessage(eventId, topic, partitionKey,
                topic, 1, payload, Map.of(), Instant.now());
    }

    /** 领域事件 → 消息：eventType/version 来自 {@code @EventType}，校验只做一次。 */
    public static EventMessage of(String eventId, String topic, String partitionKey,
                                  DomainEvent event, byte[] payload) {
        Objects.requireNonNull(event, "event");
        return new EventMessage(eventId, topic, partitionKey,
                EventTypeRegistry.logicalName(event.getClass()),
                EventTypeRegistry.schemaVersion(event.getClass()),
                payload, Map.of(), Instant.now());
    }

    /** topic 等于事件逻辑名（约定：一个事件类型一个 topic）。 */
    public static EventMessage from(String eventId, DomainEvent event, byte[] payload) {
        return of(eventId, EventTypeRegistry.logicalName(event.getClass()), null, event, payload);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventMessage that)) {
            return false;
        }
        return eventVersion == that.eventVersion
                && Objects.equals(eventId, that.eventId)
                && Objects.equals(topic, that.topic)
                && Objects.equals(partitionKey, that.partitionKey)
                && Objects.equals(eventType, that.eventType)
                && Arrays.equals(payload, that.payload)
                && Objects.equals(headers, that.headers)
                && Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(eventId, topic, partitionKey, eventType, eventVersion, headers, occurredAt);
        return 31 * result + Arrays.hashCode(payload);
    }

    /** 脱敏：不输出 payload。 */
    @Override
    public String toString() {
        return "EventMessage{eventId='" + eventId + "', topic='" + topic
                + "', partitionKey='" + partitionKey + "', eventType='" + eventType
                + "', eventVersion=" + eventVersion + ", payloadBytes=" + payload.length
                + ", occurredAt=" + occurredAt + '}';
    }
}
