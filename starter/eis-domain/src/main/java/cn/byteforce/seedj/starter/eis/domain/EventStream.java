package cn.byteforce.seedj.starter.eis.domain;

import java.util.List;

/**
 * 一条事件流在某个时刻的视图。
 *
 * <p>{@code currentVersion} 取自流头记录，而不是 {@code events.size()}：
 * 只加载尾部（快照之后）时两者并不相等。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EventStream {

    private final String streamId;

    private final long currentVersion;

    private final List<DomainEvent> events;

    /**
     * 构造事件流视图。
     *
     * @param streamId       流标识
     * @param currentVersion 流当前版本，不允许为负数
     * @param events         本次加载到的事件（按版本升序）
     * @throws IllegalArgumentException 版本为负数
     */
    public EventStream(String streamId, long currentVersion, List<DomainEvent> events) {
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must be >= 0, got: " + currentVersion);
        }
        this.streamId = streamId;
        this.currentVersion = currentVersion;
        this.events = List.copyOf(events);
    }

    /**
     * 返回空流（版本 0）。
     *
     * @param streamId 流标识
     * @return 空流
     */
    public static EventStream empty(String streamId) {
        return new EventStream(streamId, 0L, List.of());
    }

    /**
     * 返回流标识。
     *
     * @return 流标识
     */
    public String streamId() {
        return streamId;
    }

    /**
     * 返回流当前版本。
     *
     * @return 流头记录的版本
     */
    public long currentVersion() {
        return currentVersion;
    }

    /**
     * 返回本次加载到的事件。
     *
     * @return 事件列表（不可变，按版本升序）
     */
    public List<DomainEvent> events() {
        return events;
    }

    /**
     * 是否没有任何事件。
     *
     * @return 事件列表为空时为 {@code true}
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }
}
