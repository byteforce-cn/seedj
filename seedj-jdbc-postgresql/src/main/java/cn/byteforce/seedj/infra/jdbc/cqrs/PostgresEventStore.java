package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.EventStream;
import cn.byteforce.seedj.cqrs.IdCodec;
import cn.byteforce.seedj.cqrs.StreamId;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.EventTypeRegistrar;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventDispatch;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;
import cn.byteforce.seedj.eventbus.EventBusErrorCode;
import cn.byteforce.seedj.eventbus.EventBusException;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL 事件存储：按流持久化、按期望版本原子追加、按版本升序读取。
 *
 * <p>物理模型是一张 {@code event_stream} 表：{@code (aggregate_type, stream_key, version)}
 * 唯一，版本从 1 连续递增。{@code append} 先校验流头，再用一条批量 INSERT 写入；
 * 并发追加由唯一约束裁决，失败方收到 {@code CONCURRENCY_CONFLICT} 且不会留下半条流。</p>
 *
 * <p>事件的物理编码交给 {@link EventPayloadSerializer}：默认实现使用 Jackson JSON，
 * 业务可以注入 Avro/Protobuf 等实现。读取时需要把事件逻辑名解析成事件类，因此本类
 * 同时实现 {@link EventTypeRegistrar}；{@link cn.byteforce.seedj.cqrs.EventSourcedRepository}
 * 会在加载前把聚合处理的事件类型注册进来，应用重启后第一次读取也能解析。</p>
 *
 * <p>事务：{@code append} 使用 {@code TransactionTemplate}（REQUIRED）。独立调用时自己
 * 开启事务；在命令事务内调用时加入调用方事务，保证事件流、outbox 与命令终态同生共死。</p>
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresEventStore<ID> implements EventStore<ID>, EventTypeRegistrar {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SELECT_HEAD = """
            SELECT COALESCE(MAX(version), 0) AS max_version, COUNT(*) AS event_count
            FROM event_stream
            WHERE aggregate_type = ? AND stream_key = ?
            """;

    private static final String SELECT_EVENTS = """
            SELECT version, event_type, event_version, payload
            FROM event_stream
            WHERE aggregate_type = ? AND stream_key = ?
            ORDER BY version
            """;

    private static final String INSERT = """
            INSERT INTO event_stream
                (aggregate_type, stream_key, version, event_type, event_version, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    private final IdCodec<ID> codec;

    private final EventPayloadSerializer serializer;

    private final EventTypeRegistry eventTypes;

    private final TransactionTemplate transactions;

    /**
     * 使用内置 Jackson JSON 序列化。
     *
     * @param jdbc       JDBC 模板（必须带 DataSource）
     * @param codec      标识编解码
     * @param eventTypes 事件类型注册表；由仓储在加载时自动填充
     */
    public PostgresEventStore(JdbcTemplate jdbc, IdCodec<ID> codec,
                              EventTypeRegistry eventTypes) {
        this(jdbc, codec, new JacksonEventSerializer(), eventTypes);
    }

    /**
     * 使用自定义事件序列化。
     *
     * @param jdbc       JDBC 模板（必须带 DataSource）
     * @param codec      标识编解码
     * @param serializer 事件 payload 序列化
     * @param eventTypes 事件类型注册表；由仓储在加载时自动填充
     */
    public PostgresEventStore(JdbcTemplate jdbc, IdCodec<ID> codec,
                              EventPayloadSerializer serializer,
                              EventTypeRegistry eventTypes) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes");
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new CqrsException(CqrsErrorCode.STORE_UNAVAILABLE,
                    "JdbcTemplate must be backed by a DataSource");
        }
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public EventStream<ID> read(StreamId<ID> streamId) {
        Objects.requireNonNull(streamId, "streamId");
        String streamKey = encode(streamId);
        List<StoredEvent> rows = jdbc.query(SELECT_EVENTS,
                (rs, rowNum) -> new StoredEvent(
                        rs.getLong("version"),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getBytes("payload")),
                streamId.aggregateType(), streamKey);
        if (rows.isEmpty()) {
            return EventStream.empty(streamId);
        }
        List<DomainEvent> events = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            StoredEvent row = rows.get(index);
            long expected = index + 1L;
            if (row.version() != expected) {
                throw new CqrsException(CqrsErrorCode.CORRUPT_STREAM,
                        "stream " + streamId + " has a gap: expected version " + expected
                                + " but found " + row.version());
            }
            events.add(deserialize(streamId, row));
        }
        return new EventStream<>(streamId, events.size(), events);
    }

    @Override
    public long append(StreamId<ID> streamId, long expectedVersion,
                       List<? extends DomainEvent> events) {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return expectedVersion;
        }
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be >= 0");
        }
        String streamKey = encode(streamId);
        List<PreparedEvent> prepared = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            prepared.add(prepare(events.get(index), Math.addExact(expectedVersion, index + 1L)));
        }
        return transactions.execute(status ->
                appendWithin(streamId, streamKey, expectedVersion, prepared));
    }

    @Override
    public void register(Class<?> aggregateType) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        for (Class<?> eventType : EventDispatch.handledEventTypes(aggregateType)) {
            registerType(eventType);
        }
    }

    private long appendWithin(StreamId<ID> streamId, String streamKey, long expectedVersion,
                              List<PreparedEvent> events) {
        StreamHead head = jdbc.queryForObject(SELECT_HEAD,
                (rs, rowNum) -> new StreamHead(
                        rs.getLong("max_version"), rs.getLong("event_count")),
                streamId.aggregateType(), streamKey);
        if (head == null) {
            throw new CqrsException(CqrsErrorCode.CORRUPT_STREAM,
                    "cannot read stream head for " + streamId);
        }
        if (head.eventCount() > 0 && head.maxVersion() != head.eventCount()) {
            throw new CqrsException(CqrsErrorCode.CORRUPT_STREAM,
                    "stream " + streamId + " has " + head.eventCount()
                            + " events but max version " + head.maxVersion());
        }
        if (head.maxVersion() != expectedVersion) {
            throw conflict(streamId, expectedVersion, head.maxVersion(), null);
        }
        try {
            jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    PreparedEvent event = events.get(index);
                    statement.setString(1, streamId.aggregateType());
                    statement.setString(2, streamKey);
                    statement.setLong(3, event.version());
                    statement.setString(4, event.eventType());
                    statement.setInt(5, event.eventVersion());
                    statement.setBytes(6, event.payload());
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });
        } catch (DuplicateKeyException e) {
            throw new CqrsException(CqrsErrorCode.CONCURRENCY_CONFLICT,
                    "stream " + streamId + " was appended concurrently", e);
        }
        return Math.addExact(expectedVersion, events.size());
    }

    private PreparedEvent prepare(DomainEvent event, long version) {
        Objects.requireNonNull(event, "event");
        try {
            eventTypes.register(event.getClass());
        } catch (RuntimeException e) {
            throw new CqrsException(CqrsErrorCode.INTERNAL_ERROR,
                    "cannot register event type " + event.getClass().getName(), e);
        }
        byte[] payload;
        try {
            payload = serializer.serialize(event);
        } catch (RuntimeException e) {
            throw new CqrsException(CqrsErrorCode.INTERNAL_ERROR,
                    "cannot serialize event " + event.eventType(), e);
        }
        if (payload == null) {
            throw new CqrsException(CqrsErrorCode.INTERNAL_ERROR,
                    "event serializer returned null for " + event.eventType());
        }
        return new PreparedEvent(version, event.eventType(), event.schemaVersion(), payload);
    }

    private DomainEvent deserialize(StreamId<ID> streamId, StoredEvent row) {
        Class<? extends DomainEvent> eventType;
        try {
            eventType = eventTypes.resolve(row.eventType());
        } catch (IllegalArgumentException e) {
            throw new CqrsException(CqrsErrorCode.CORRUPT_STREAM,
                    "unknown event type " + row.eventType() + " in stream " + streamId
                            + "; register the aggregate event types before reading", e);
        }
        try {
            DomainEvent event = serializer.deserialize(row.payload(), eventType);
            if (event == null) {
                throw new IllegalStateException("serializer returned null");
            }
            return event;
        } catch (RuntimeException e) {
            throw new CqrsException(CqrsErrorCode.CORRUPT_STREAM,
                    "cannot deserialize event " + row.eventType() + " in stream " + streamId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerType(Class<?> type) {
        if (!DomainEvent.class.isAssignableFrom(type)) {
            return;
        }
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            eventTypes.register((Class<? extends DomainEvent>) type);
        }
        if (type.isSealed() && type.getPermittedSubclasses() != null) {
            for (Class<?> permitted : type.getPermittedSubclasses()) {
                registerType(permitted);
            }
        }
    }

    private String encode(StreamId<ID> streamId) {
        String encoded = codec.encode(streamId.id());
        if (encoded == null || encoded.isBlank()) {
            throw new CqrsException(CqrsErrorCode.INVALID_ID,
                    "IdCodec.encode returned a blank key for " + streamId);
        }
        return encoded;
    }

    private static CqrsException conflict(StreamId<?> streamId, long expected, long actual,
                                          Throwable cause) {
        String message = "stream " + streamId + " expected version " + expected
                + " but was " + actual;
        return cause == null
                ? new CqrsException(CqrsErrorCode.CONCURRENCY_CONFLICT, message)
                : new CqrsException(CqrsErrorCode.CONCURRENCY_CONFLICT, message, cause);
    }

    private record StreamHead(long maxVersion, long eventCount) {
    }

    private record StoredEvent(long version, String eventType, int eventVersion, byte[] payload) {
    }

    private record PreparedEvent(long version, String eventType, int eventVersion, byte[] payload) {
    }

    /**
     * 内置 Jackson JSON 序列化：只作为直接构造时的默认值；Spring 装配会注入应用
     * 配置好的 {@link EventPayloadSerializer}。
     */
    private static final class JacksonEventSerializer implements EventPayloadSerializer {

        @Override
        public byte[] serialize(Object payload) {
            try {
                return JSON.writeValueAsBytes(payload);
            } catch (Exception e) {
                throw new EventBusException(EventBusErrorCode.SERIALIZATION_FAILED,
                        "cannot serialize event payload", e);
            }
        }

        @Override
        public <T> T deserialize(byte[] payload, Class<T> type) {
            try {
                return JSON.readValue(payload, type);
            } catch (Exception e) {
                throw new EventBusException(EventBusErrorCode.SERIALIZATION_FAILED,
                        "cannot deserialize event payload as " + type.getName(), e);
            }
        }
    }
}
