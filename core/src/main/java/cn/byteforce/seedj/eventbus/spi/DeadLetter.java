package cn.byteforce.seedj.eventbus.spi;

import cn.byteforce.seedj.eventbus.EventMessage;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条进入死信的消息。
 */
public record DeadLetter(
        String id,
        String topic,
        String group,
        EventMessage message,
        DeliveryFailure failure,
        Instant deadLetteredAt) {

    public DeadLetter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(deadLetteredAt, "deadLetteredAt");
    }
}
