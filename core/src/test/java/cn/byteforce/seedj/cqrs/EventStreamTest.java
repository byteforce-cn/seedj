package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件流：全量历史与版本必须自洽，空流用 version 0 表示。 */
class EventStreamTest {

    private static final StreamId<UUID> STREAM =
            new StreamId<>("hr.employee", UUID.fromString("018f2e3a-1234-7000-8000-000000000001"));

    @Test
    @DisplayName("空流版本为 0 且 isEmpty 为真")
    void emptyStream() {
        EventStream<UUID> stream = EventStream.empty(STREAM);

        assertThat(stream.streamId()).isEqualTo(STREAM);
        assertThat(stream.version()).isZero();
        assertThat(stream.events()).isEmpty();
        assertThat(stream.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("版本必须等于事件数")
    void versionMustMatchEventCount() {
        EventStream<UUID> stream = new EventStream<>(STREAM, 1L, List.of(new Onboarded()));

        assertThat(stream.version()).isEqualTo(1L);
        assertThat(stream.isEmpty()).isFalse();
        assertThatThrownBy(() -> new EventStream<>(STREAM, 2L, List.of(new Onboarded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must equal events.size()");
    }

    @Test
    @DisplayName("事件与流标识不可为空，版本不可为负")
    void guards() {
        assertThatThrownBy(() -> new EventStream<>(null, 0L, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("streamId");
        assertThatThrownBy(() -> new EventStream<>(STREAM, -1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be >= 0");
        // List.copyOf 先于显式 null 检查执行，因此 null 元素表现为 NPE
        java.util.List<DomainEvent> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new EventStream<>(STREAM, 1L, withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null 事件列表按空流处理")
    void nullEventsBecomeEmpty() {
        EventStream<UUID> stream = new EventStream<>(STREAM, 0L, null);

        assertThat(stream.events()).isEmpty();
        assertThat(stream.isEmpty()).isTrue();
    }

    @EventType("hr.employee.onboarded")
    static final class Onboarded implements DomainEvent {}
}
