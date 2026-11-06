package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.ClaimRequest;
import cn.byteforce.seedj.outbox.spi.ClaimToken;
import cn.byteforce.seedj.outbox.spi.ClaimedRecord;
import cn.byteforce.seedj.outbox.spi.OutboxFailure;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL outbox 实现：事务性写入 + 分区内顺序投递 + 租约。
 *
 * <p>业务事务提交 = 记录必达（at-least-once）。分区内顺序由
 * {@code (partition_key, sequence)} 唯一约束 + claim 的 {@code NOT EXISTS} 保证。
 */
public class PostgresOutboxStore implements OutboxStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };

    private static final String INSERT = """
            INSERT INTO outbox (id, message_id, destination, partition_key, sequence, content_type,
                                content_version, payload, headers, occurred_at, aggregate_type,
                                status, attempts)
            VALUES (?, ?, ?, ?,
                    COALESCE((SELECT MAX(sequence) FROM outbox
                              WHERE partition_key IS NOT DISTINCT FROM ?), 0) + 1,
                    ?, ?, ?, ?::jsonb, ?, ?, 'PENDING', 0)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private static final String RECLAIM = """
            UPDATE outbox
            SET status = 'PENDING', lease_id = NULL, lease_until = NULL
            WHERE status = 'PROCESSING' AND lease_until < now()
            """;

    private static final String CLAIM = """
            UPDATE outbox o
            SET status = 'PROCESSING', lease_id = gen_random_uuid()::text,
                lease_until = now() + (? || ' milliseconds')::interval,
                attempts = attempts + 1
            WHERE o.id IN (
                SELECT o2.id FROM outbox o2
                WHERE o2.status = 'PENDING'
                  AND (o2.next_retry_at IS NULL OR o2.next_retry_at <= now())
                  AND NOT EXISTS (SELECT 1 FROM outbox p
                                  WHERE p.partition_key = o2.partition_key
                                    AND p.sequence < o2.sequence
                                    AND p.status IN ('PENDING', 'PROCESSING', 'DEAD'))
                ORDER BY o2.partition_key, o2.sequence
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING o.id, o.message_id, o.destination, o.partition_key, o.sequence,
                      o.content_type, o.content_version, o.payload, o.headers, o.occurred_at,
                      o.attempts, o.lease_id, o.lease_until
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int appendMaxAttempts;
    private final IdGenerator<String> idGenerator;

    public PostgresOutboxStore(JdbcTemplate jdbcTemplate, IdGenerator<String> idGenerator) {
        this(jdbcTemplate, 3, idGenerator);
    }

    public PostgresOutboxStore(JdbcTemplate jdbcTemplate, int appendMaxAttempts,
                               IdGenerator<String> idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.appendMaxAttempts = Math.max(1, appendMaxAttempts);
        this.idGenerator = idGenerator;
    }

    @Override
    public void append(OutboxRecord record) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OutboxException(OutboxErrorCode.TX_REQUIRED,
                    "outbox append must be called inside an active transaction");
        }
        String headers = toJson(record.headers());
        for (int attempt = 1; attempt <= appendMaxAttempts; attempt++) {
            try {
                jdbcTemplate.update(INSERT,
                        idGenerator.id(),
                        record.messageId(),
                        record.destination(),
                        record.partitionKey(),
                        record.partitionKey(),
                        record.contentType(),
                        record.contentVersion(),
                        record.payload(),
                        headers,
                        java.sql.Timestamp.from(record.occurredAt()),
                        record.aggregateType());
                return;
            } catch (DuplicateKeyException e) {
                // (partition_key, sequence) 撞车：并发写同一分区，重试取下一个序号
                if (attempt == appendMaxAttempts) {
                    throw new OutboxException(OutboxErrorCode.STORE_UNAVAILABLE,
                            "cannot allocate sequence for partition " + record.partitionKey(), e);
                }
            }
        }
    }

    @Override
    public List<ClaimedRecord> claim(ClaimRequest request) {
        // 先回收过期租约，再领取：两步都是幂等的，避免 UNION + FOR UPDATE 的语法限制
        jdbcTemplate.update(RECLAIM);
        return jdbcTemplate.query(CLAIM, new OutboxRowMapper(request.lease()),
                request.lease().toMillis(), request.limit());
    }

    @Override
    public void markSent(ClaimToken token) {
        int updated = jdbcTemplate.update("""
                UPDATE outbox SET status = 'SENT', lease_id = NULL, lease_until = NULL
                WHERE id = ? AND lease_id = ? AND status = 'PROCESSING'
                """, token.recordId(), token.leaseId());
        if (updated == 0) {
            throw new OutboxException(OutboxErrorCode.LEASE_LOST,
                    "lease lost, cannot mark sent: " + token.recordId());
        }
    }

    @Override
    public void markFailed(ClaimToken token, OutboxFailure failure, Duration retryDelay) {
        int updated;
        if (failure.retryable()) {
            updated = jdbcTemplate.update("""
                    UPDATE outbox
                    SET status = 'PENDING',
                        next_retry_at = now() + (? || ' milliseconds')::interval,
                        lease_id = NULL, lease_until = NULL
                    WHERE id = ? AND lease_id = ? AND status = 'PROCESSING'
                    """, retryDelay.toMillis(), token.recordId(), token.leaseId());
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE outbox
                    SET status = 'DEAD', dead_at = now(), last_error = ?,
                        lease_id = NULL, lease_until = NULL
                    WHERE id = ? AND lease_id = ? AND status = 'PROCESSING'
                    """, failure.errorMessage(), token.recordId(), token.leaseId());
        }
        if (updated == 0) {
            throw new OutboxException(OutboxErrorCode.LEASE_LOST,
                    "lease lost, cannot mark failed: " + token.recordId());
        }
    }

    @Override
    public boolean supportsRenewal() {
        return true;
    }

    @Override
    public boolean renewLease(ClaimToken token) {
        return jdbcTemplate.update("""
                UPDATE outbox SET lease_until = now() + (? || ' milliseconds')::interval
                WHERE id = ? AND lease_id = ? AND status = 'PROCESSING'
                """, token.leaseDuration().toMillis(), token.recordId(), token.leaseId()) > 0;
    }

    @Override
    public void retry(String recordId, String operator) {
        int updated = jdbcTemplate.update("""
                UPDATE outbox
                SET status = 'PENDING', next_retry_at = NULL, attempts = 0,
                    lease_id = NULL, lease_until = NULL, dead_at = NULL, last_error = NULL
                WHERE id = ? AND status IN ('FAILED', 'DEAD')
                """, recordId);
        auditOrFail(updated, recordId, "RETRY", operator);
    }

    @Override
    public void discard(String recordId, String operator) {
        int updated = jdbcTemplate.update("""
                UPDATE outbox SET status = 'SENT', lease_id = NULL, lease_until = NULL,
                    dead_at = NULL, last_error = NULL
                WHERE id = ? AND status IN ('FAILED', 'DEAD')
                """, recordId);
        auditOrFail(updated, recordId, "DISCARD", operator);
    }

    private void auditOrFail(int updated, String recordId, String action, String operator) {
        if (updated == 0) {
            throw new OutboxException(OutboxErrorCode.RECORD_NOT_FOUND,
                    "record not found or not in dead status: " + recordId);
        }
        jdbcTemplate.update("""
                INSERT INTO outbox_audit (id, record_id, action, operated_by, occurred_at)
                VALUES (?, ?, ?, ?, now())
                """, idGenerator.id(), recordId, action, operator);
    }

    private static String toJson(Map<String, String> headers) {
        try {
            return JSON.writeValueAsString(headers);
        } catch (Exception e) {
            throw new OutboxException(OutboxErrorCode.INVALID_RECORD,
                    "cannot serialize headers", e);
        }
    }

    private static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, HEADERS);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private final class OutboxRowMapper implements RowMapper<ClaimedRecord> {

        private final Duration lease;

        private OutboxRowMapper(Duration lease) {
            this.lease = lease;
        }

        @Override
        public ClaimedRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            String recordId = rs.getString("id");
            String leaseId = rs.getString("lease_id");
            Instant leaseUntil = rs.getTimestamp("lease_until").toInstant();

            OutboxRecord record = new OutboxRecord(
                    rs.getString("message_id"),
                    rs.getString("destination"),
                    rs.getString("partition_key"),
                    rs.getString("content_type"),
                    rs.getInt("content_version"),
                    rs.getBytes("payload"),
                    fromJson(rs.getString("headers")),
                    rs.getTimestamp("occurred_at").toInstant());

            return new ClaimedRecord(recordId, record, rs.getInt("attempts"),
                    new ClaimToken(recordId, leaseId, lease), leaseUntil);
        }
    }
}
