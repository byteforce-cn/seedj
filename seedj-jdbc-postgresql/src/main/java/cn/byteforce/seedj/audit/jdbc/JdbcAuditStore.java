package cn.byteforce.seedj.audit.jdbc;

import cn.byteforce.seedj.audit.AuditEntry;
import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import cn.byteforce.seedj.audit.spi.AuditStore;
import cn.byteforce.seedj.core.utils.CanonicalJsonHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

/**
 * PostgreSQL 审计链存储。
 *
 * <p>{@code nextSequenceAndPrevHash} 对 {@code audit_head} 单行加 {@code FOR UPDATE}，
 * 使并发 append 在审计处串行；序号只在 {@code updateHead} 时推进，回滚则序号不消耗。</p>
 */
public final class JdbcAuditStore implements AuditStore<String> {

    private static final String INSERT = """
            INSERT INTO audit_entry (id, sequence, occurred_at, actor, action, resource_type,
                                     resource_id, payload, hash, prev_hash, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """;

    private static final String SELECT_ENTRY = """
            SELECT id, sequence, occurred_at, actor, action, resource_type, resource_id,
                   payload, hash, prev_hash, signature
            FROM audit_entry
            """;

    private static final RowMapper<AuditEntry<String>> ENTRY_MAPPER = (rs, rowNum) ->
            new AuditEntry<>(
                    rs.getString("id"),
                    rs.getLong("sequence"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("actor"),
                    rs.getString("action"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    CanonicalJsonHelper.parse(rs.getString("payload")),
                    rs.getString("hash"),
                    rs.getString("prev_hash"),
                    rs.getString("signature"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Head nextSequenceAndPrevHash() {
        List<Head> rows = jdbcTemplate.query(
                "SELECT sequence, last_hash FROM audit_head WHERE id = 'audit' FOR UPDATE",
                (rs, rowNum) -> new Head(rs.getLong("sequence"), rs.getString("last_hash")));
        if (rows.isEmpty()) {
            throw new AuditException(AuditErrorCode.STORE_UNAVAILABLE,
                    "audit_head is missing; run the audit migration first");
        }
        Head current = rows.get(0);
        return new Head(current.getSequence() + 1, current.getHash());
    }

    @Override
    public void insert(AuditEntry<String> entry) {
        jdbcTemplate.update(INSERT,
                entry.getId(),
                entry.getSequence(),
                Timestamp.from(entry.getTimestamp()),
                entry.getActor(),
                entry.getAction(),
                entry.getResourceType(),
                entry.getResourceId(),
                CanonicalJsonHelper.canonicalize(entry.getPayload()),
                entry.getHash(),
                entry.getPrevHash(),
                entry.getSignature());
    }

    @Override
    public void updateHead(String hash) {
        jdbcTemplate.update(
                "UPDATE audit_head SET sequence = sequence + 1, last_hash = ? WHERE id = 'audit'",
                hash);
    }

    @Override
    public List<AuditEntry<String>> loadAll() {
        return jdbcTemplate.query(SELECT_ENTRY + " ORDER BY sequence", ENTRY_MAPPER);
    }

    @Override
    public Head head() {
        List<Head> rows = jdbcTemplate.query(
                "SELECT sequence, last_hash FROM audit_head WHERE id = 'audit'",
                (rs, rowNum) -> new Head(rs.getLong("sequence"), rs.getString("last_hash")));
        if (rows.isEmpty()) {
            throw new AuditException(AuditErrorCode.STORE_UNAVAILABLE,
                    "audit_head is missing; run the audit migration first");
        }
        return rows.get(0);
    }
}
