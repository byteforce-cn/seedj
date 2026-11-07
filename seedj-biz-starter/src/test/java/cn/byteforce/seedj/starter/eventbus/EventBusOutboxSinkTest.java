package cn.byteforce.seedj.starter.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EventBusOutboxSinkTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("OutboxRecord 映射回 EventMessage 后写 transport")
    void deliversMappedMessage() {
        RecordingTransport transport = new RecordingTransport();
        EventBusOutboxSink sink = new EventBusOutboxSink(provider(transport));

        sink.deliver(record()).toCompletableFuture().join();

        assertThat(transport.sent).hasSize(1);
        EventMessage message = transport.sent.get(0);
        assertThat(message.eventId()).isEqualTo("m1");
        assertThat(message.topic()).isEqualTo("order.cancelled");
        assertThat(message.partitionKey()).isEqualTo("agg-1");
        assertThat(message.eventType()).isEqualTo("order.cancelled");
        assertThat(message.eventVersion()).isEqualTo(2);
        assertThat(new String(message.payload(), StandardCharsets.UTF_8)).isEqualTo("payload");
        assertThat(message.headers()).containsEntry("trace", "t-1");
        assertThat(message.occurredAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("没有 transport 时返回可重试失败，不丢消息")
    void failsWhenTransportMissing() {
        EventBusOutboxSink sink = new EventBusOutboxSink(provider(null));

        CompletionStage<Void> stage = sink.deliver(record());

        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(OutboxException.class);
        assertThat(stage.toCompletableFuture().exceptionNow())
                .isInstanceOf(OutboxException.class)
                .extracting(e -> ((OutboxException) e).getErrorCode())
                .isEqualTo(OutboxErrorCode.DELIVERY_FAILED);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static OutboxRecord record() {
        return new OutboxRecord(
                "m1",
                "order.cancelled",
                "agg-1",
                "order.cancelled",
                2,
                "payload".getBytes(StandardCharsets.UTF_8),
                Map.of("trace", "t-1"),
                AT);
    }
}
