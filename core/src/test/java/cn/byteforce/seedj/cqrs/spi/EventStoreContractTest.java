package cn.byteforce.seedj.cqrs.spi;

import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.EventStream;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EventStore} 契约测试套件。
 *
 * <p>实现方只需要提供 {@link #newStore()}：每个用例拿到一个空存储。
 * 这条契约的要点是"读全量历史 + 期望版本 CAS"，与序列化格式、列类型无关，
 * 所以测试只断言语义，不碰物理实现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class EventStoreContractTest {

    /** 每个用例一个空存储。 */
    protected abstract EventStore<String> newStore();

    private EventStore<String> store;

    private final StreamId<String> stream = StreamId.of("test.order", "o-1");

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    @Test
    @DisplayName("读取不存在的流返回空流，版本为 0")
    void readUnknownStreamReturnsEmpty() {
        EventStream<String> result = store.read(stream);

        assertThat(result.streamId()).isEqualTo(stream);
        assertThat(result.version()).isZero();
        assertThat(result.events()).isEmpty();
    }

    @Test
    @DisplayName("新建流的期望版本为 0，返回值等于事件数")
    void appendNewStreamStartsAtZero() {
        long version = store.append(stream, 0, List.of(new Created("o-1")));

        assertThat(version).isEqualTo(1);
        assertThat(store.read(stream).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("读取按版本升序返回全量历史")
    void readReturnsFullHistoryInOrder() {
        store.append(stream, 0, List.of(new Created("o-1")));
        store.append(stream, 1, List.of(new Confirmed("o-1"), new Renamed("o-1", "new")));

        EventStream<String> result = store.read(stream);

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.events()).containsExactly(
                new Created("o-1"), new Confirmed("o-1"), new Renamed("o-1", "new"));
    }

    @Test
    @DisplayName("期望版本过期时抛 CONCURRENCY_CONFLICT，且不写入任何事件")
    void appendStaleVersionConflictsWithoutWriting() {
        store.append(stream, 0, List.of(new Created("o-1")));

        assertThatThrownBy(() -> store.append(stream, 0, List.of(new Confirmed("o-1"))))
                .isInstanceOfSatisfying(CqrsException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CqrsErrorCode.CONCURRENCY_CONFLICT));

        assertThat(store.read(stream).events()).containsExactly(new Created("o-1"));
    }

    @Test
    @DisplayName("空事件列表是 no-op：不校验版本、不创建流")
    void emptyAppendIsNoOp() {
        assertThat(store.append(stream, 0, List.of())).isZero();
        assertThat(store.read(stream).isEmpty()).isTrue();

        store.append(stream, 0, List.of(new Created("o-1")));

        assertThat(store.append(stream, 99, List.of())).isEqualTo(99);
        assertThat(store.read(stream).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同标识的流互不影响")
    void streamsAreIsolatedByAggregateId() {
        StreamId<String> other = StreamId.of("test.order", "o-2");

        store.append(stream, 0, List.of(new Created("o-1")));
        store.append(other, 0, List.of(new Created("o-2"), new Confirmed("o-2")));

        assertThat(store.read(stream).version()).isEqualTo(1);
        assertThat(store.read(other).version()).isEqualTo(2);
        assertThat(store.read(stream).events()).containsExactly(new Created("o-1"));
    }

    @Test
    @DisplayName("不同聚合类型的同名标识是两条流")
    void streamsAreIsolatedByAggregateType() {
        StreamId<String> invoice = StreamId.of("test.invoice", "o-1");

        store.append(stream, 0, List.of(new Created("o-1")));
        store.append(invoice, 0, List.of(new Confirmed("x")));

        assertThat(store.read(stream).events()).containsExactly(new Created("o-1"));
        assertThat(store.read(invoice).events()).containsExactly(new Confirmed("x"));
    }

    @EventType("test.order.created")
    public record Created(String orderId) implements DomainEvent {
    }

    @EventType("test.order.confirmed")
    public record Confirmed(String orderId) implements DomainEvent {
    }

    @EventType("test.order.renamed")
    public record Renamed(String orderId, String name) implements DomainEvent {
    }
}
