package cn.byteforce.seedj.starter.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DefaultEventPublisherTest {

    @EventType(value = "order.cancelled", version = 3)
    record OrderCancelled(String orderId) implements DomainEvent {}

    private final RecordingTransport transport = new RecordingTransport();
    private final RecordingWriter writer = new RecordingWriter();

    @Test
    @DisplayName("有 outbox 时写 outbox，不直接写 transport")
    void outboxTakesPrecedence() {
        DefaultEventPublisher publisher = publisher(store(), writer, transport, serializer());

        publisher.publish(message());

        assertThat(writer.records).hasSize(1);
        assertThat(transport.sent).isEmpty();
        OutboxRecord record = writer.records.get(0);
        assertThat(record.messageId()).isEqualTo("e1");
        assertThat(record.destination()).isEqualTo("topic");
        assertThat(record.contentType()).isEqualTo("topic");
        assertThat(record.contentVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有 outbox 时直接写 transport")
    void directTransportWithoutOutbox() {
        DefaultEventPublisher publisher = publisher(null, null, transport, serializer());

        publisher.publish(message());

        assertThat(transport.sent).hasSize(1);
        assertThat(writer.records).isEmpty();
    }

    @Test
    @DisplayName("只有 store 没有 writer 时退回 transport，不抛 NoSuchBeanDefinition")
    void fallsBackWhenWriterMissing() {
        DefaultEventPublisher publisher = publisher(store(), null, transport, serializer());

        publisher.publish(message());

        assertThat(transport.sent).hasSize(1);
    }

    @Test
    @DisplayName("既没有 outbox 也没有 transport 时抛 NO_TRANSPORT")
    void missingTransportFails() {
        DefaultEventPublisher publisher = publisher(null, null, null, serializer());

        assertThatThrownBy(() -> publisher.publish(message()))
                .isInstanceOf(EventBusException.class)
                .extracting(e -> ((EventBusException) e).getErrorCode())
                .isEqualTo(EventBusErrorCode.NO_TRANSPORT);
    }

    @Test
    @DisplayName("publish(DomainEvent) 走序列化与 @EventType 元数据")
    void publishDomainEventUsesSerializer() {
        DefaultEventPublisher publisher = publisher(store(), writer, transport, serializer());

        publisher.publish(new OrderCancelled("o-1"));

        assertThat(writer.records).hasSize(1);
        OutboxRecord record = writer.records.get(0);
        assertThat(record.destination()).isEqualTo("order.cancelled");
        assertThat(record.contentType()).isEqualTo("order.cancelled");
        assertThat(record.contentVersion()).isEqualTo(3);
        assertThat(new String(record.payload(), StandardCharsets.UTF_8)).isEqualTo("serialized");
    }

    @Test
    @DisplayName("显式 eventId 成为 outbox message_id，供命令回执复用")
    void publishWithExplicitEventIdUsesItInOutbox() {
        DefaultEventPublisher publisher = publisher(store(), writer, transport, serializer());
        UUID eventId = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

        publisher.publish(new OrderCancelled("o-1"), eventId);

        assertThat(writer.records).hasSize(1);
        assertThat(writer.records.get(0).messageId()).isEqualTo(eventId.toString());
    }

    @Test
    @DisplayName("没有序列化器时 publish(DomainEvent) 抛 NO_SERIALIZER")
    void missingSerializerFails() {
        DefaultEventPublisher publisher = publisher(store(), writer, transport, null);

        assertThatThrownBy(() -> publisher.publish(new OrderCancelled("o-1")))
                .isInstanceOf(EventBusException.class)
                .extracting(e -> ((EventBusException) e).getErrorCode())
                .isEqualTo(EventBusErrorCode.NO_SERIALIZER);
    }

    private static DefaultEventPublisher publisher(
            OutboxStore store,
            OutboxWriter writer,
            EventBusTransport transport,
            EventPayloadSerializer serializer) {
        return new DefaultEventPublisher(
                provider(store),
                provider(writer),
                provider(transport),
                provider(serializer),
                () -> "event-1",
                EventBusMetrics.noop(),
                cn.byteforce.seedj.starter.observability.TracePropagation.noop());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static OutboxStore store() {
        return mock(OutboxStore.class);
    }

    private static EventPayloadSerializer serializer() {
        return new EventPayloadSerializer() {
            @Override
            public byte[] serialize(Object payload) {
                return "serialized".getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static EventMessage message() {
        return new EventMessage(
                "e1",
                "topic",
                "key",
                "topic",
                1,
                "payload".getBytes(StandardCharsets.UTF_8),
                null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static final class RecordingWriter implements OutboxWriter {
        private final List<OutboxRecord> records = new ArrayList<>();

        @Override
        public void append(OutboxRecord record) {
            records.add(record);
        }
    }
}
