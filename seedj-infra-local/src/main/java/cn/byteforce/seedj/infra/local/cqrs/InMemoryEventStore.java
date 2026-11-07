package cn.byteforce.seedj.infra.local.cqrs;

import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.EventStream;
import cn.byteforce.seedj.cqrs.IdCodec;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.domain.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存事件存储：单进程、无持久化。
 *
 * <p>用于本地开发、单元测试与契约测试。生产环境请换 JDBC/对象存储实现； 两者的语义完全一致，因为都遵守 {@link EventStore} 的契约。
 *
 * <p>写入用 {@code compute} 保证同一条流的 CAS 是原子的：并发追加只有一个成功。
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryEventStore<ID> implements EventStore<ID> {

    private final IdCodec<ID> codec;

    private final ConcurrentMap<String, List<DomainEvent>> streams = new ConcurrentHashMap<>();

    public InMemoryEventStore(IdCodec<ID> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public EventStream<ID> read(StreamId<ID> streamId) {
        Objects.requireNonNull(streamId, "streamId");
        List<DomainEvent> history = streams.get(streamId.canonical(codec));
        if (history == null || history.isEmpty()) {
            return EventStream.empty(streamId);
        }
        return new EventStream<>(streamId, history.size(), history);
    }

    @Override
    public long append(
            StreamId<ID> streamId, long expectedVersion, List<? extends DomainEvent> events) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return expectedVersion;
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be >= 0");
        }

        AtomicLong appended = new AtomicLong();
        streams.compute(
                streamId.canonical(codec),
                (key, current) -> {
                    List<DomainEvent> history = current == null ? List.of() : current;
                    if (history.size() != expectedVersion) {
                        throw new CqrsException(
                                CqrsErrorCode.CONCURRENCY_CONFLICT,
                                "stream "
                                        + streamId
                                        + " expected version "
                                        + expectedVersion
                                        + " but was "
                                        + history.size());
                    }
                    List<DomainEvent> next = new ArrayList<>(history.size() + events.size());
                    next.addAll(history);
                    next.addAll(events);
                    appended.set(next.size());
                    return List.copyOf(next);
                });
        return appended.get();
    }
}
