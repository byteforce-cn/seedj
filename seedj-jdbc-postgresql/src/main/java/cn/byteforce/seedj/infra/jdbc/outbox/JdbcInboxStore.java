package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.outbox.spi.InboxRecord;
import cn.byteforce.seedj.outbox.spi.InboxStatus;
import cn.byteforce.seedj.outbox.spi.InboxStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL Inbox：{@code (consumer_group, event_id)} 唯一保证消费幂等。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcInboxStore implements InboxStore {

    private static final RowMapper<InboxRecord> MAPPER = (rs, rowNum) -> new InboxRecord(
            rs.getString("consumer_group"),
            rs.getObject("event_id", UUID.class),
            InboxStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
            rs.getInt("attempts"),
            rs.getString("last_error"),
            instant(rs, "received_at"),
            instant(rs, "done_at"),
            instant(rs, "dead_at"));

    private final JdbcTemplate jdbc;

    public JdbcInboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String consumerGroup, UUID eventId) {
        int inserted = jdbc.update("""
                INSERT INTO inbox_message (consumer_group, event_id, status, attempts)
                VALUES (?, ?, 'processing', 0)
                ON CONFLICT (consumer_group, event_id) DO NOTHING
                """, consumerGroup, eventId);
        return inserted > 0;
    }

    @Override
    public Optional<InboxRecord> find(String consumerGroup, UUID eventId) {
        List<InboxRecord> rows = jdbc.query("""
                SELECT * FROM inbox_message WHERE consumer_group = ? AND event_id = ?
                """, MAPPER, consumerGroup, eventId);
        return rows.stream().findFirst();
    }

    @Override
    public void markDone(String consumerGroup, UUID eventId) {
        jdbc.update("""
                UPDATE inbox_message
                SET status = 'done', done_at = now(), last_error = NULL
                WHERE consumer_group = ? AND event_id = ? AND status = 'processing'
                """, consumerGroup, eventId);
    }

    @Override
    public void markDead(String consumerGroup, UUID eventId, String error) {
        jdbc.update("""
                UPDATE inbox_message
                SET status = 'dead', dead_at = now(), last_error = ?
                WHERE consumer_group = ? AND event_id = ? AND status = 'processing'
                """, error, consumerGroup, eventId);
    }

    @Override
    public boolean resetDead(String consumerGroup, UUID eventId) {
        return jdbc.update("""
                UPDATE inbox_message
                SET status = 'processing', attempts = attempts + 1,
                    dead_at = NULL, last_error = NULL
                WHERE consumer_group = ? AND event_id = ? AND status = 'dead'
                """, consumerGroup, eventId) > 0;
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
