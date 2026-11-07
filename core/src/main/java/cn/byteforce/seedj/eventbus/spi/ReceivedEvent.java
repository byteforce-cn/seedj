package cn.byteforce.seedj.eventbus.spi;

import cn.byteforce.seedj.eventbus.EventMessage;
import java.time.Instant;
import java.util.Objects;

/**
 * 一条已被当前实例领取（claim）的消息。
 *
 * <p>{@code deliveryId} 是本次领取的凭证：ack / retry / deadLetter 必须原样带回。 租约到期后该凭证失效，消息会被其他实例重新领取。
 */
public record ReceivedEvent(
        String deliveryId, String group, EventMessage message, int attempts, Instant leaseUntil) {

    public ReceivedEvent {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
    }
}
