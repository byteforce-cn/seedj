package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.event.DomainEvent;
import java.util.List;
import java.util.Objects;

/**
 * 一条流的完整历史。
 *
 * <p>{@code version} 等于 {@code events.size()}：本记录只表示"全量历史"， 不含快照。两者不一致说明适配器漏读或多读，构造期直接失败，而不是让聚合
 * 以错误的版本重放——那种错误只会在下一次写入时以并发冲突的形式暴露。
 *
 * <p>空流用 {@code version == 0 && events.isEmpty()} 表示，不存在 {@code null}。
 *
 * @param <ID> 聚合标识类型
 * @param streamId 流标识
 * @param version 流版本（已持久化事件数）
 * @param events 按版本升序的事件
 * @author ByteForce
 * @since 1.0.0
 */
public record EventStream<ID>(StreamId<ID> streamId, long version, List<DomainEvent> events) {

    public EventStream {
        Objects.requireNonNull(streamId, "streamId");
        events = events == null ? List.of() : List.copyOf(events);
        if (events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("events must not contain null");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0, got: " + version);
        }
        if (version != events.size()) {
            throw new IllegalArgumentException(
                    "version must equal events.size(): version="
                            + version
                            + ", events="
                            + events.size()
                            + " for stream "
                            + streamId);
        }
    }

    /** 不存在的流（版本 0、无事件）。 */
    public static <ID> EventStream<ID> empty(StreamId<ID> streamId) {
        return new EventStream<>(streamId, 0L, List.of());
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
