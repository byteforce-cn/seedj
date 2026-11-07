package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 命令记录存储。
 *
 * <p>{@code insert} 用 {@code ON CONFLICT DO NOTHING} 让唯一约束裁决并发同键； {@code complete} 只更新 {@code
 * pending} 行，终态不可覆盖。JSONB 列以字符串绑定， 与既有 JDBC 存储保持一致。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresCommandStore implements CommandStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private static final TypeReference<List<String>> IDS_TYPE = new TypeReference<>() {};

    private static final RowMapper<CommandRecord> MAPPER = PostgresCommandStore::map;

    private static final String SELECT = "SELECT * FROM command_record";

    private final JdbcTemplate jdbc;

    /**
     * 构造存储。
     *
     * @param jdbc JDBC 模板
     */
    public PostgresCommandStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<CommandRecord> find(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<CommandRecord> findByIdempotencyKey(UUID issuer, String idempotencyKey) {
        return jdbc
                .query(
                        SELECT + " WHERE issuer = ? AND idempotency_key = ?",
                        MAPPER,
                        issuer,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public boolean insert(CommandRecord record) {
        int inserted =
                jdbc.update(
                        """
                INSERT INTO command_record
                    (id, type, issued_at, issuer, payload, idempotency_key,
                     payload_fingerprint, status, rejection_code, rejection_reason,
                     event_ids, locator, version, target_ref, created_at, lease_until)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                        record.id(),
                        record.type(),
                        Timestamp.from(record.issuedAt()),
                        record.issuer(),
                        JSON.writeValueAsString(record.payload()),
                        record.idempotencyKey(),
                        record.payloadFingerprint(),
                        record.status().wireName(),
                        record.rejectionCode(),
                        record.rejectionReason(),
                        JSON.writeValueAsString(
                                record.eventIds().stream().map(UUID::toString).toList()),
                        record.locator(),
                        record.version(),
                        record.targetRef(),
                        Timestamp.from(record.createdAt()),
                        Timestamp.from(record.leaseUntil()));
        return inserted == 1;
    }

    @Override
    public void complete(CommandRecord record) {
        int updated =
                jdbc.update(
                        """
                UPDATE command_record
                SET status = ?, rejection_code = ?, rejection_reason = ?,
                    event_ids = ?::jsonb, locator = ?, version = ?, target_ref = ?
                WHERE id = ? AND status = 'pending'
                """,
                        record.status().wireName(),
                        record.rejectionCode(),
                        record.rejectionReason(),
                        JSON.writeValueAsString(
                                record.eventIds().stream().map(UUID::toString).toList()),
                        record.locator(),
                        record.version(),
                        record.targetRef(),
                        record.id());
        if (updated == 1) {
            return;
        }
        CommandRecord existing =
                find(record.id())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "command record not found: " + record.id()));
        if (existing.equals(record)) {
            return;
        }
        throw new IllegalStateException(
                "command record already completed: "
                        + record.id()
                        + " ("
                        + existing.status().wireName()
                        + ")");
    }

    @Override
    public void abandon(UUID id) {
        jdbc.update("DELETE FROM command_record WHERE id = ? AND status = 'pending'", id);
    }

    @Override
    public long expirePending(java.time.Instant now) {
        return jdbc.update(
                "DELETE FROM command_record WHERE status = 'pending'" + " AND lease_until < ?",
                Timestamp.from(now));
    }

    private static CommandRecord map(ResultSet rs, int rowNum) throws SQLException {
        UUID locator = rs.getObject("locator", UUID.class);
        long version = rs.getLong("version");
        Long nullableVersion = rs.wasNull() ? null : version;
        return new CommandRecord(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                instant(rs, "issued_at"),
                rs.getObject("issuer", UUID.class),
                payload(rs.getString("payload")),
                rs.getString("idempotency_key"),
                rs.getString("payload_fingerprint"),
                CommandStatus.fromWire(rs.getString("status")),
                rs.getString("rejection_code"),
                rs.getString("rejection_reason"),
                eventIds(rs.getString("event_ids")),
                locator,
                nullableVersion,
                rs.getString("target_ref"),
                instant(rs, "created_at"),
                instant(rs, "lease_until"));
    }

    private static Map<String, Object> payload(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, PAYLOAD_TYPE);
    }

    private static List<UUID> eventIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, IDS_TYPE).stream().map(UUID::fromString).toList();
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
