package cn.byteforce.seedj.outbox.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条消费幂等记录。
 *
 * @param consumerGroup 消费组
 * @param eventId       事件 ID
 * @param status        状态
 * @param attempts      尝试次数
 * @param lastError     最近错误
 * @param receivedAt    接收时间
 * @param doneAt        完成时间
 * @param deadAt        死信时间
 * @author ByteForce
 * @since 1.0.0
 */
public record InboxRecord(String consumerGroup, UUID eventId, InboxStatus status,
                          int attempts, String lastError, Instant receivedAt,
                          Instant doneAt, Instant deadAt) {
}
