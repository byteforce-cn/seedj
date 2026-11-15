package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.mq.v2.MqV2Message;
import cn.byteforce.seedj.eventbus.EventMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投递账本/死信里保存的消息快照。
 *
 * <p>只保存重建 {@link EventMessage} 所需的字段：payload 不复制到死信之外的地方， 正常重投靠按位点回读 MQ。时间用 epoch 毫秒而不是 {@code
 * Instant}，KV 记录的 JSON 不依赖 JavaTime 模块。
 */
record StoredMessage(
        String eventId,
        String topic,
        String partitionKey,
        String eventType,
        int eventVersion,
        byte[] payload,
        Map<String, String> headers,
        long occurredAtMs) {

    private static final String EVENT_ID = "eventId";
    private static final String EVENT_TYPE = "eventType";
    private static final String EVENT_VERSION = "eventVersion";
    private static final String OCCURRED_AT = "occurredAt";

    static StoredMessage of(EventMessage message) {
        return new StoredMessage(
                message.eventId(),
                message.topic(),
                message.partitionKey(),
                message.eventType(),
                message.eventVersion(),
                message.payload(),
                message.headers(),
                message.occurredAt().toEpochMilli());
    }

    EventMessage toEventMessage() {
        return new EventMessage(
                eventId,
                topic,
                partitionKey,
                eventType,
                eventVersion,
                payload,
                headers,
                Instant.ofEpochMilli(occurredAtMs));
    }

    /**
     * MQ 消息 → {@link EventMessage}：发布侧把 eventId/eventType/eventVersion/occurredAt 写在 headers
     * 里，这里把它们还原成字段并从句柄里摘掉，保证 consume 侧看到的消息形状 与 publish 侧一致。
     */
    static EventMessage toEvent(MqV2Message message) {
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        String eventId = headers.remove(EVENT_ID);
        String eventType = value(headers.remove(EVENT_TYPE), message.topic());
        int eventVersion = version(headers.remove(EVENT_VERSION));
        long occurredAt = parseLong(headers.remove(OCCURRED_AT), message.timestampMs());
        String partitionKey =
                message.key().length == 0
                        ? null
                        : new String(message.key(), StandardCharsets.UTF_8);
        return new EventMessage(
                eventId == null
                        ? message.topic() + ":" + message.partition() + ":" + message.offset()
                        : eventId,
                message.topic(),
                partitionKey,
                eventType,
                eventVersion,
                message.payload(),
                headers,
                Instant.ofEpochMilli(occurredAt));
    }

    /** retention 已清掉原消息时，死信只保留定位信息。 */
    static StoredMessage retentionGone(String topic) {
        return new StoredMessage(
                topic + ":retention-expired",
                topic,
                null,
                topic,
                1,
                new byte[0],
                Map.of(),
                System.currentTimeMillis());
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int version(String value) {
        try {
            return value == null ? 1 : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
