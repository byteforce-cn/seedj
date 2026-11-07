package cn.byteforce.seedj.outbox.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Inbox 消费幂等端口：同一 {@code (consumerGroup, eventId)} 只处理一次。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface InboxStore {

    /**
     * 尝试认领；已处理、处理中或死信记录返回 {@code false}。
     *
     * @param consumerGroup 消费组
     * @param eventId 事件 ID
     * @return 是否新认领成功
     */
    boolean claim(String consumerGroup, UUID eventId);

    Optional<InboxRecord> find(String consumerGroup, UUID eventId);

    void markDone(String consumerGroup, UUID eventId);

    void markDead(String consumerGroup, UUID eventId, String error);

    boolean resetDead(String consumerGroup, UUID eventId);
}
