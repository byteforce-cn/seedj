package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventApplier;
import cn.byteforce.seedj.domain.event.EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件溯源仓储：读流重放、按期望版本追加、无事件时不写存储。 */
class EventSourcedRepositoryTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    @Test
    @DisplayName("流不存在时加载为空")
    void loadMissingStreamReturnsEmpty() {
        RecordingStore store =
                new RecordingStore(EventStream.empty(new StreamId<>("hr.counter", ID)));

        assertThat(new CounterRepository(store).load(ID)).isEmpty();
        assertThat(store.reads).containsExactly(new StreamId<>("hr.counter", ID));
    }

    @Test
    @DisplayName("加载时重放全部历史并把版本校正到流头")
    void loadReplaysHistory() {
        RecordingStore store =
                new RecordingStore(
                        new EventStream<>(
                                new StreamId<>("hr.counter", ID),
                                2L,
                                List.of(new Added(1), new Added(4))));

        Counter counter = new CounterRepository(store).load(ID).orElseThrow();

        assertThat(counter.total()).isEqualTo(5);
        assertThat(counter.version()).isEqualTo(2L);
        assertThat(counter.hasUncommittedEvents()).isFalse();
    }

    @Test
    @DisplayName("保存按期望版本追加事件并清空待提交列表")
    void saveAppendsWithExpectedVersion() {
        RecordingStore store =
                new RecordingStore(
                        new EventStream<>(
                                new StreamId<>("hr.counter", ID), 1L, List.of(new Added(1))));
        CounterRepository repository = new CounterRepository(store);
        Counter counter = repository.load(ID).orElseThrow();
        counter.add(2);

        repository.save(counter);

        assertThat(store.appendedStreamId).isEqualTo(new StreamId<>("hr.counter", ID));
        assertThat(store.expectedVersion).isEqualTo(1L);
        assertThat(store.appendedEvents).hasSize(1);
        assertThat(counter.hasUncommittedEvents()).isFalse();
        assertThat(counter.version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("没有待提交事件时不写存储")
    void saveWithoutEventsIsNoop() {
        RecordingStore store =
                new RecordingStore(
                        new EventStream<>(
                                new StreamId<>("hr.counter", ID), 1L, List.of(new Added(1))));
        CounterRepository repository = new CounterRepository(store);

        repository.save(repository.load(ID).orElseThrow());

        assertThat(store.appends).isZero();
    }

    @Test
    @DisplayName("存储与聚合参数不可为空")
    void guards() {
        assertThatThrownBy(() -> new CounterRepository(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("store");
        assertThatThrownBy(() -> new CounterRepository(new RecordingStore(null)).load(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new CounterRepository(new RecordingStore(null)).save(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregate");
    }

    // ---------------------------------------------------------------- fixtures

    @EventType("hr.counter.added")
    static final class Added implements DomainEvent {

        private final int amount;

        Added(int amount) {
            this.amount = amount;
        }
    }

    static final class Counter extends AbstractAggregateRoot<UUID> implements EventApplier<Added> {

        private int total;

        Counter(UUID id) {
            super(id);
        }

        static Counter blank(UUID id) {
            return new Counter(id);
        }

        void add(int amount) {
            raise(new Added(amount));
        }

        int total() {
            return total;
        }

        protected void apply(Added event) {
            total += event.amount;
        }
    }

    static final class CounterRepository extends EventSourcedRepository<Counter, UUID> {

        CounterRepository(EventStore<UUID> store) {
            super(store);
        }

        @Override
        protected Class<Counter> aggregateType() {
            return Counter.class;
        }

        @Override
        protected Counter empty(UUID id) {
            return Counter.blank(id);
        }

        @Override
        protected String streamType() {
            return "hr.counter";
        }
    }

    static final class RecordingStore implements EventStore<UUID> {

        private final EventStream<UUID> stream;

        private final List<StreamId<UUID>> reads = new ArrayList<>();

        private int appends;

        private StreamId<UUID> appendedStreamId;

        private long expectedVersion;

        private List<? extends DomainEvent> appendedEvents = List.of();

        RecordingStore(EventStream<UUID> stream) {
            this.stream = stream;
        }

        @Override
        public EventStream<UUID> read(StreamId<UUID> streamId) {
            reads.add(streamId);
            return stream == null ? EventStream.empty(streamId) : stream;
        }

        @Override
        public long append(
                StreamId<UUID> streamId, long expectedVersion, List<? extends DomainEvent> events) {
            appends++;
            appendedStreamId = streamId;
            this.expectedVersion = expectedVersion;
            appendedEvents = List.copyOf(events);
            return expectedVersion + events.size();
        }
    }
}
