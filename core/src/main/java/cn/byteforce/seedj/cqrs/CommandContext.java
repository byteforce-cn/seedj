package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.id.IdGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次命令执行的上文：目标、当前行、时间、操作者与写范围。
 *
 * <p>{@link #event(String, Map)} 自动分配事件 id 与幂等键
 * {@code commandId:type:index}；index 在单次命令内递增。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CommandContext {

    private final UUID commandId;

    private final UUID targetId;

    private final Optional<TemporalRow> current;

    private final Instant now;

    private final UUID actor;

    private final Caller caller;

    private final ScopePredicate writeScope;

    private final ObjectSchema schema;

    private final IdGenerator<UUID> ids;

    private final Long expectedVersion;

    private final AtomicInteger eventIndex = new AtomicInteger();

    /**
     * 构造上下文。
     *
     * @param commandId  命令 ID
     * @param targetId   目标对象 ID
     * @param current    当前行
     * @param now        系统时间
     * @param actor      操作者
     * @param caller     调用方
     * @param writeScope 写范围
     * @param schema     对象 schema
     * @param ids        事件 id 生成器
     */
    public CommandContext(UUID commandId, UUID targetId, Optional<TemporalRow> current,
                          Instant now, UUID actor, Caller caller, ScopePredicate writeScope,
                          ObjectSchema schema, IdGenerator<UUID> ids) {
        this(commandId, targetId, current, now, actor, caller, writeScope, schema, ids, null);
    }

    /**
     * 构造上下文（带期望版本）。
     *
     * @param commandId       命令 ID
     * @param targetId        目标对象 ID
     * @param current         当前行
     * @param now             系统时间
     * @param actor           操作者
     * @param caller          调用方
     * @param writeScope      写范围
     * @param schema          对象 schema
     * @param ids             事件 id 生成器
     * @param expectedVersion 期望版本；{@code null} 不校验
     */
    public CommandContext(UUID commandId, UUID targetId, Optional<TemporalRow> current,
                          Instant now, UUID actor, Caller caller, ScopePredicate writeScope,
                          ObjectSchema schema, IdGenerator<UUID> ids, Long expectedVersion) {
        this.commandId = Objects.requireNonNull(commandId, "commandId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.current = Objects.requireNonNull(current, "current");
        this.now = Objects.requireNonNull(now, "now");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.writeScope = Objects.requireNonNull(writeScope, "writeScope");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.expectedVersion = expectedVersion;
    }

    public UUID commandId() {
        return commandId;
    }

    public UUID targetId() {
        return targetId;
    }

    public Optional<TemporalRow> current() {
        return current;
    }

    public Instant now() {
        return now;
    }

    public UUID actor() {
        return actor;
    }

    public Caller caller() {
        return caller;
    }

    public ScopePredicate writeScope() {
        return writeScope;
    }

    public ObjectSchema schema() {
        return schema;
    }

    /**
     * 期望的对象版本。
     *
     * @return 期望版本；{@code null} 表示不校验
     */
    public Long expectedVersion() {
        return expectedVersion;
    }

    /**
     * 构造一条事件。
     *
     * @param type    事件类型
     * @param payload 载荷
     * @return 事件
     */
    public CommandEvent event(String type, Map<String, Object> payload) {
        int index = eventIndex.getAndIncrement();
        return new CommandEvent(ids.id(), type, now, now, actor, List.of(targetId), payload,
                commandId + ":" + type + ":" + index, 1);
    }
}
