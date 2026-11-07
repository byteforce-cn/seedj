package cn.byteforce.seedj.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventMessageTest {

    @EventType(value = "order.cancelled", version = 3)
    record OrderCancelled(String orderId) implements DomainEvent {}

    @Test
    @DisplayName("领域事件 → 消息：eventType/version 来自 @EventType，topic 默认等于逻辑名")
    void fromDomainEvent() {
        EventMessage message = EventMessage.from("e-1", new OrderCancelled("o-1"), "{}".getBytes());

        assertThat(message.topic()).isEqualTo("order.cancelled");
        assertThat(message.eventType()).isEqualTo("order.cancelled");
        assertThat(message.eventVersion()).isEqualTo(3);
        assertThat(message.eventId()).isEqualTo("e-1");
        assertThat(message.headers()).isEmpty();
    }

    @Test
    @DisplayName("payload 不可变：入参与出参都不是内部数组")
    void payloadIsDefensive() {
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        EventMessage message = EventMessage.of("e-1", "topic", "key", payload);

        payload[0] = 'X';
        assertThat(new String(message.payload(), StandardCharsets.UTF_8)).isEqualTo("hi");

        message.payload()[0] = 'X';
        assertThat(new String(message.payload(), StandardCharsets.UTF_8)).isEqualTo("hi");
    }

    @Test
    @DisplayName("equals/hashCode 按内容比较 payload")
    void equalityUsesPayloadContent() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        EventMessage a = new EventMessage("e1", "t", "k", "t", 1, new byte[] {1, 2}, Map.of(), at);
        EventMessage b = new EventMessage("e1", "t", "k", "t", 1, new byte[] {1, 2}, Map.of(), at);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("非法消息在构造期失败")
    void validation() {
        assertThatThrownBy(
                        () ->
                                new EventMessage(
                                        "e1", "t", null, "t", 0, new byte[0], null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new EventMessage("e1", "t", null, "t", 1, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("toString 不输出 payload")
    void toStringRedacts() {
        EventMessage message = EventMessage.of("e-1", "topic", "k", "secret".getBytes());
        assertThat(message.toString()).doesNotContain("secret").contains("topic");
    }
}
