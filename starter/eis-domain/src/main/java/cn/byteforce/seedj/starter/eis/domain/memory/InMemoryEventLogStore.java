package cn.byteforce.seedj.starter.eis.domain.memory;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventLogStore;
import cn.byteforce.seedj.starter.eis.domain.EventStream;
import cn.byteforce.seedj.starter.eis.domain.OptimisticLockingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件日志的内存实现：用于开发、单元测试与无数据库环境。
 *
 * <p>语义与生产实现保持一致（流版本 CAS、按版本升序、尾部加载），但<b>不持久化</b>：
 * 进程重启即丢失。生产环境必须使用 PostgreSQL 实现。</p>
 *
 * <p>每个流一把内部锁；{@link #append} 的「校验版本 + 写入」在同一临界区内完成，
 * 因此并发写同一流只有一个能成功。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryEventLogStore implements EventLogStore {

    private final Map<String, StreamState> streams = new ConcurrentHashMap<>();

    /**
     * 加载流的全部事件。
     *
     * @param streamId 流标识
     * @return 事件流视图；不存在时为空流
     */
    @Override
    public EventStream loadStream(String streamId) {
        return loadStream(streamId, 0L);
    }

    /**
     * 加载版本号大于 {@code fromVersion} 的事件。
     *
     * @param streamId    流标识
     * @param fromVersion 已覆盖到的版本（不含）
     * @return 事件流视图
     */
    @Override
    public EventStream loadStream(String streamId, long fromVersion) {
        StreamState state = streams.get(streamId);
        if (state == null) {
            return EventStream.empty(streamId);
        }
        synchronized (state) {
            int offset = (int) Math.min(Math.max(fromVersion, 0L), state.events.size());
            return new EventStream(streamId, state.version, state.events.subList(offset, state.events.size()));
        }
    }

    /**
     * 原子追加事件。
     *
     * @param streamId        流标识
     * @param aggregateType   聚合类型名（内存实现仅用于排障，不存储）
     * @param expectedVersion 乐观锁基准版本，0 表示新建
     * @param events          待追加事件
     * @throws OptimisticLockingException 版本冲突
     * @throws IllegalArgumentException   事件列表为空
     */
    @Override
    public void append(String streamId, String aggregateType, long expectedVersion, List<DomainEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        StreamState state = streams.computeIfAbsent(streamId, key -> new StreamState());
        synchronized (state) {
            if (state.version != expectedVersion) {
                throw new OptimisticLockingException(streamId, expectedVersion, state.version);
            }
            state.events.addAll(events);
            state.version += events.size();
        }
    }

    /**
     * 单个流的状态与锁。
     */
    private static final class StreamState {

        private final List<DomainEvent> events = new ArrayList<>();

        private long version;

        private StreamState() {
        }
    }
}
