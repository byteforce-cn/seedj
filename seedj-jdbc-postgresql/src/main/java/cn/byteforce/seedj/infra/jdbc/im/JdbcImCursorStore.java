package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.im.spi.ImCursorStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 游标存储：两类游标都用「插入或取较大值」的单条 UPSERT 收敛。
 *
 * <p>只前进的语义放在 SQL 里（{@code GREATEST}），因此多端并发上报不需要加锁，也不会回退。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcImCursorStore implements ImCursorStore {

    private final JdbcTemplate jdbc;

    private final Clock clock;

    /**
     * 构造存储。
     *
     * @param jdbc JDBC 模板
     * @param clock 取时入口
     */
    public JdbcImCursorStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public long readSeq(UUID conversationId, String subjectId) {
        List<Long> values =
                jdbc.queryForList(
                        """
                        SELECT last_read_seq FROM im_read_cursor
                        WHERE conversation_id = ? AND subject_id = ?
                        """,
                        Long.class,
                        conversationId,
                        subjectId);
        return values.isEmpty() || values.get(0) == null ? 0L : values.get(0);
    }

    @Override
    public long advanceRead(UUID conversationId, String subjectId, long seq) {
        Long value =
                jdbc.queryForObject(
                        """
                        INSERT INTO im_read_cursor
                            (conversation_id, subject_id, last_read_seq, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (conversation_id, subject_id) DO UPDATE
                        SET last_read_seq =
                                GREATEST(im_read_cursor.last_read_seq, EXCLUDED.last_read_seq),
                            updated_at = EXCLUDED.updated_at
                        RETURNING last_read_seq
                        """,
                        Long.class,
                        conversationId,
                        subjectId,
                        seq,
                        timestamp(clock.instant()));
        return value == null ? 0L : value;
    }

    @Override
    public long syncSeq(String tenantId, String subjectId, String deviceId) {
        List<Long> values =
                jdbc.queryForList(
                        """
                        SELECT seq FROM im_sync_cursor
                        WHERE tenant_id = ? AND subject_id = ? AND device_id = ?
                        """,
                        Long.class,
                        tenantId,
                        subjectId,
                        deviceId);
        return values.isEmpty() || values.get(0) == null ? 0L : values.get(0);
    }

    @Override
    public long advanceSync(String tenantId, String subjectId, String deviceId, long seq) {
        Long value =
                jdbc.queryForObject(
                        """
                        INSERT INTO im_sync_cursor (tenant_id, subject_id, device_id, seq, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, subject_id, device_id) DO UPDATE
                        SET seq = GREATEST(im_sync_cursor.seq, EXCLUDED.seq),
                            updated_at = EXCLUDED.updated_at
                        RETURNING seq
                        """,
                        Long.class,
                        tenantId,
                        subjectId,
                        deviceId,
                        seq,
                        timestamp(clock.instant()));
        return value == null ? 0L : value;
    }

    private static Object timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
