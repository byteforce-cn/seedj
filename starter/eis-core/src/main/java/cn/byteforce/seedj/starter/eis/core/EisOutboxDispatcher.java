package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox 分发 SPI（v1 进程内实现；跨服务通道预留 = coord MQ，落地条件见
 * {@code eis/docs/LANDING-IAM.md §3.4.2}）。
 *
 * <p>outbox 是真相源（与命令同事务落库），本 SPI 是"分发动作"的替换点：
 * v1 默认实现仅调用 {@link EisNotifyHook}（fail-open）；首个跨服务消费者出现时，
 * 以 coord MQ relay 实现本 SPI（Poll+Ack、幂等键 = event_id），业务代码不变。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisOutboxDispatcher {

    /**
     * 分发一条 outbox 记录（提交后调用）。
     *
     * @param entry outbox 记录
     */
    void dispatch(EisOutboxEntry entry);

    /**
     * outbox 记录（topic + 事件载荷）。
     */
    final class EisOutboxEntry {

        private final UUID id;

        private final UUID eventId;

        private final String topic;

        private final JsonNode payload;

        private final Instant createdAt;

        /**
         * 构造 outbox 记录。
         *
         * @param id        outbox 记录 ID
         * @param eventId   事件 ID（消费侧幂等键）
         * @param topic     主题
         * @param payload   事件载荷
         * @param createdAt 创建时刻
         */
        public EisOutboxEntry(UUID id, UUID eventId, String topic, JsonNode payload, Instant createdAt) {
            this.id = id;
            this.eventId = eventId;
            this.topic = topic;
            this.payload = payload;
            this.createdAt = createdAt;
        }

        /**
         * 获取记录 ID。
         *
         * @return outbox ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取事件 ID。
         *
         * @return 事件 ID
         */
        public UUID getEventId() {
            return eventId;
        }

        /**
         * 获取主题。
         *
         * @return 主题
         */
        public String getTopic() {
            return topic;
        }

        /**
         * 获取载荷。
         *
         * @return 事件载荷
         */
        public JsonNode getPayload() {
            return payload;
        }

        /**
         * 获取创建时刻。
         *
         * @return 创建时刻
         */
        public Instant getCreatedAt() {
            return createdAt;
        }
    }
}
