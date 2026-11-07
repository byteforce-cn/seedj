package cn.byteforce.seedj.starter.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.starter.observability.TracePropagation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 发布端把追踪上下文写进 EventMessage.headers；outbox 映射原样保留。 */
class EventBusTracePropagationTest {

    private final AtomicReference<OutboxRecord> appended = new AtomicReference<>();

    private final OutboxWriter writer = appended::set;

    private final TracePropagation tracing =
            new TracePropagation() {
                @Override
                public Map<String, String> inject(Map<String, String> headers) {
                    Map<String, String> result = new HashMap<>(headers);
                    result.put("traceparent", "00-trace-1-span-1-01");
                    return result;
                }

                @Override
                public Scope extract(Map<String, String> headers) {
                    return () -> {};
                }
            };

    @Test
    @DisplayName("publish 注入 traceparent，outbox 记录带着它落库")
    void injectsTraceparentIntoOutboxRecord() {
        DefaultEventPublisher publisher =
                new DefaultEventPublisher(
                        provider(mock(OutboxStore.class)),
                        provider(writer),
                        provider(null),
                        provider(null),
                        () -> "event-1",
                        EventBusMetrics.noop(),
                        tracing);

        EventMessage message =
                new EventMessage(
                        "event-1",
                        "employee.onboarded",
                        "e-1",
                        "employee.onboarded",
                        1,
                        new byte[] {1},
                        Map.of(),
                        Instant.now());

        publisher.publish(message);

        assertThat(appended.get()).isNotNull();
        assertThat(appended.get().headers()).containsEntry("traceparent", "00-trace-1-span-1-01");
        assertThat(message.headers()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
