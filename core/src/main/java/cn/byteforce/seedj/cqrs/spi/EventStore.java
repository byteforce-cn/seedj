package cn.byteforce.seedj.cqrs.spi;

import cn.byteforce.seedj.cqrs.EventStream;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.domain.event.DomainEvent;

import java.util.List;

/**
 * 事件存储端口（写模型）。
 *
 * <p>只做两件事：读一条流的全部历史、按期望版本追加事件。没有查询、没有订阅、
 * 没有投影——查询走读模型，发布走 {@code EventPublisher}。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>{@link #read} 永不返回 {@code null}；流不存在时返回 {@link EventStream#empty}；</li>
 *   <li>{@link #append} 必须对同一条流<b>原子</b>：并发追加只有一个成功；</li>
 *   <li>{@link #append} 的 {@code expectedVersion = 0} 表示"新建流"；</li>
 *   <li>版本不匹配抛 {@link cn.byteforce.seedj.cqrs.CqrsException}
 *       （{@code CONCURRENCY_CONFLICT}），且不得写入任何事件；</li>
 *   <li>{@code events} 为空时是 no-op，返回 {@code expectedVersion}，不校验版本；</li>
 *   <li>返回值为追加后的流版本，等于 {@code expectedVersion + events.size()}。</li>
 * </ul>
 *
 * <p>实现负责事件与流键的物理编码（列类型、序列化格式）；核心只依赖
 * {@link cn.byteforce.seedj.cqrs.IdCodec} 得到流键的规范文本。
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventStore<ID> {

    /**
     * 读取一条流的全部历史，按版本升序。
     */
    EventStream<ID> read(StreamId<ID> streamId);

    /**
     * 按期望版本追加事件。
     *
     * @return 追加后的流版本
     */
    long append(StreamId<ID> streamId, long expectedVersion, List<? extends DomainEvent> events);
}
