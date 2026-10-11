package cn.byteforce.seedj.starter.eis.core;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 事件与 outbox 存储（JDBC）：{@code event} 契约表（append-only）+
 * 部署侧 {@code eis_outbox} 表。
 *
 * <p>outbox 与命令同事务写入（真相源）；分发动作（进程内通知 / 未来 coord MQ relay）
 * 由 {@link EisOutboxDispatcher} 承接，分发成功后回填 {@code dispatched_at}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisEventStore {

    private final JdbcTemplate jdbc;

    /**
     * 构造事件存储。
     *
     * @param jdbc JDBC 模板（参与外层事务）
     */
    public EisEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入事件。
     *
     * @param event 事件
     */
    public void insert(EisEvent event) {
        jdbc.update("INSERT INTO event (id, type, occurred_at, recorded_at, actor, subjects, payload,"
                        + " idempotency_key, version)"
                        + " VALUES (?, ?, ?, ?, ?, CAST(? AS uuid[]), CAST(? AS jsonb), ?, ?)",
                event.getId(), event.getType(),
                OffsetDateTime.ofInstant(event.getOccurredAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(event.getRecordedAt(), ZoneOffset.UTC),
                event.getActor(), toArrayLiteral(event.getSubjects()),
                event.getPayload() == null ? "{}" : event.getPayload().toString(),
                event.getIdempotencyKey(), event.getVersion());
    }

    /**
     * 写入 outbox 记录（与事件同事务）。
     *
     * @param entry outbox 记录
     */
    public void insertOutbox(EisOutboxDispatcher.EisOutboxEntry entry) {
        jdbc.update("INSERT INTO eis_outbox (id, event_id, topic, payload, created_at)"
                        + " VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                entry.getId(), entry.getEventId(), entry.getTopic(),
                entry.getPayload() == null ? "{}" : entry.getPayload().toString(),
                OffsetDateTime.ofInstant(entry.getCreatedAt(), ZoneOffset.UTC));
    }

    /**
     * 回填分发时间。
     *
     * @param outboxId    outbox 记录 ID
     * @param dispatchedAt 分发时刻
     */
    public void markDispatched(UUID outboxId, Instant dispatchedAt) {
        jdbc.update("UPDATE eis_outbox SET dispatched_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(dispatchedAt, ZoneOffset.UTC), outboxId);
    }

    /**
     * 查询未分发记录（供 relay / 运维重放；v1 无跨服务消费者时为诊断面）。
     *
     * @param limit 最大条数
     * @return 未分发记录（按创建时间升序）
     */
    public List<EisOutboxDispatcher.EisOutboxEntry> findUndispatched(int limit) {
        return jdbc.query("SELECT id, event_id, topic, payload::text, created_at FROM eis_outbox"
                        + " WHERE dispatched_at IS NULL ORDER BY created_at ASC LIMIT ?",
                (rs, rowNum) -> new EisOutboxDispatcher.EisOutboxEntry(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        CanonicalJson.parse(rs.getString(4)),
                        rs.getObject(5, OffsetDateTime.class).toInstant()),
                limit);
    }

    private static String toArrayLiteral(List<UUID> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append('}').toString();
    }
}
