package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordBodies;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.LedgerHash;
import cn.byteforce.seedj.ai.LedgerAudit;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL 决策账本：每个 {@code append*} 单事务，按分区行锁串行推进哈希链。
 *
 * <p>追加顺序：建链头（{@code on conflict do nothing}）→ {@code select ... for update} 锁本分区 → 取 {@code
 * sequence = last_sequence + 1} 与 {@code prev_hash = last_hash} → 组装记录体 → 算 {@link LedgerHash} →
 * 插记录 → 推进链头。行锁让同分区的追加串行，不同分区互不阻塞。
 *
 * <p>幂等：{@code intent} 与 {@code outcome} 每个 decision 至多一条，先查后插；并发下先查后插会撞车， 最终由 partial unique index
 * 兜底——插入冲突时再查一次，返回已存在的那条。{@code approval} 与 {@code reconciled} 不幂等： 前者是每次观测一条审批事实，后者是每次核对一条观察。
 *
 * <p><b>跨租户 fail-closed</b>：{@code partial unique index} 按 {@code decision_id} 全库唯一，同一 {@code
 * decision_id} 只属于一个租户。别的租户复用同一 {@code decision_id} 时一律拒绝（抛 {@link IllegalArgumentException}），
 * 既不返回已有租户的记录（不泄露存在性），也不写第二条影子记录——{@code find} / {@code byDecision} 是租户作用域 的，返回别人的记录等于越权。
 *
 * <p>记录体（{@code body}）由 {@link DecisionRecordBodies} 组装，本类不自己拼 map：{@code record_hash} 覆盖整个
 * 记录体，两套实现写出的记录体不同，同一份决策在两套账本里的哈希就不同。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresDecisionLedger implements DecisionLedger {

    private static final String COLUMNS =
            """
            record_id, decision_id, record_kind, partition, sequence, prev_hash,
            record_hash, recorded_at, tenant_id, body
            """;

    private static final String INSERT_RECORD =
            """
            INSERT INTO agent_decision_record
                (record_id, decision_id, record_kind, partition, sequence, prev_hash,
                 record_hash, recorded_at, tenant_id, schema_version, body)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String ENSURE_CHAIN =
            """
            INSERT INTO agent_decision_chain (partition, last_sequence, last_hash)
            VALUES (?, 0, ?) ON CONFLICT (partition) DO NOTHING
            """;

    private static final String LOCK_CHAIN =
            """
            SELECT last_sequence, last_hash FROM agent_decision_chain
            WHERE partition = ? FOR UPDATE
            """;

    private static final String ADVANCE_CHAIN =
            "UPDATE agent_decision_chain SET last_sequence = ?, last_hash = ? WHERE partition = ?";

    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<>() {};

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RowMapper<UUID> UUID_ROW = (rs, rowNum) -> rs.getObject(1, UUID.class);

    private static final String PENDING_OUTCOMES =
            """
            select i.decision_id from agent_decision_record i
             where i.tenant_id = ? and i.record_kind = 'intent' and i.recorded_at < ?
               and not exists (
                 select 1 from agent_decision_record o
                  where o.decision_id = i.decision_id and o.record_kind = 'outcome')
             order by i.recorded_at limit ?
            """;

    private static final String PENDING_AUDIT =
            """
            select o.decision_id from agent_decision_record o
             where o.tenant_id = ? and o.record_kind = 'outcome' and o.recorded_at < ?
               and o.body->'effect'->>'status' = 'ok'
               and o.body->'effect'->>'audit_entry_id' is null
               and not exists (
                 select 1 from agent_decision_record r
                  where r.decision_id = o.decision_id and r.record_kind = 'reconciled'
                    and r.body->'effect'->>'audit_entry_id' is not null)
             order by o.recorded_at limit ?
            """;

    private static final String PENDING_APPROVALS =
            """
            select a.decision_id from agent_decision_record a
             where a.tenant_id = ? and a.record_kind = 'approval'
               and a.body->'approval'->>'status' = 'pending'
               and a.recorded_at < ?
               and not exists (
                 select 1 from agent_decision_record b
                  where b.decision_id = a.decision_id and b.record_kind = 'approval'
                    and b.sequence > a.sequence)
             order by a.recorded_at limit ?
            """;

    private static final RowMapper<DecisionRecord> RECORD_MAPPER =
            (rs, rowNum) ->
                    new DecisionRecord(
                            rs.getObject("record_id", UUID.class),
                            rs.getObject("decision_id", UUID.class),
                            DecisionRecordKind.fromWire(rs.getString("record_kind")),
                            rs.getString("partition"),
                            rs.getLong("sequence"),
                            rs.getString("prev_hash").trim(),
                            rs.getString("record_hash").trim(),
                            rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("tenant_id", UUID.class),
                            readBody(rs.getString("body")));

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transaction;

    /**
     * 绑定数据源。
     *
     * @param dataSource 已迁移到 {@code ai} scope 的数据源
     */
    public PostgresDecisionLedger(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public DecisionRecord appendIntent(DecisionIntent intent) {
        Objects.requireNonNull(intent, "intent");
        DecisionRecord existing = existing(intent.decisionId(), DecisionRecordKind.INTENT);
        if (existing != null) {
            return existingForTenant(existing, intent.tenantId());
        }
        try {
            return existingForTenant(
                    inTransaction(
                            intent.tenantId(),
                            intent.decisionId(),
                            DecisionRecordKind.INTENT,
                            DecisionRecordBodies.intent(intent)),
                    intent.tenantId());
        } catch (DuplicateKeyException e) {
            return existingForTenant(
                    existingAfterRace(intent.decisionId(), DecisionRecordKind.INTENT, e),
                    intent.tenantId());
        }
    }

    @Override
    public DecisionRecord appendApproval(UUID decisionId, ApprovalSnapshot snapshot) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(snapshot, "snapshot");
        UUID tenantId = requireTenant(decisionId);
        return inTransaction(
                tenantId,
                decisionId,
                DecisionRecordKind.APPROVAL,
                DecisionRecordBodies.approval(snapshot));
    }

    @Override
    public DecisionRecord appendOutcome(
            UUID decisionId, DecisionEffect effect, OutcomeSource source) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(source, "source");
        UUID tenantId = requireTenant(decisionId);
        DecisionRecord existing = existing(decisionId, DecisionRecordKind.OUTCOME);
        if (existing != null) {
            return existingForTenant(existing, tenantId);
        }
        try {
            return existingForTenant(
                    inTransaction(
                            tenantId,
                            decisionId,
                            DecisionRecordKind.OUTCOME,
                            DecisionRecordBodies.outcome(effect, source)),
                    tenantId);
        } catch (DuplicateKeyException e) {
            return existingForTenant(
                    existingAfterRace(decisionId, DecisionRecordKind.OUTCOME, e), tenantId);
        }
    }

    @Override
    public DecisionRecord appendReconciled(UUID decisionId, DecisionEffect effect, String reason) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(reason, "reason");
        UUID tenantId = requireTenant(decisionId);
        DecisionRecord outcome = existing(decisionId, DecisionRecordKind.OUTCOME);
        String supersedes = outcome == null ? null : outcome.recordId().toString();
        return inTransaction(
                tenantId,
                decisionId,
                DecisionRecordKind.RECONCILED,
                DecisionRecordBodies.reconciled(effect, reason, supersedes));
    }

    @Override
    public Optional<DecisionRecord> find(UUID tenantId, UUID recordId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recordId, "recordId");
        List<DecisionRecord> rows =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM agent_decision_record WHERE record_id = ? AND tenant_id = ?",
                        RECORD_MAPPER,
                        recordId,
                        tenantId);
        return rows.stream().findFirst();
    }

    @Override
    public List<DecisionRecord> byDecision(UUID tenantId, UUID decisionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(decisionId, "decisionId");
        return jdbc.query(
                "SELECT "
                        + COLUMNS
                        + " FROM agent_decision_record WHERE decision_id = ? AND tenant_id = ?"
                        + " ORDER BY sequence",
                RECORD_MAPPER,
                decisionId,
                tenantId);
    }

    @Override
    public List<UUID> pendingOutcomes(UUID tenantId, Instant before, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(before, "before");
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query(PENDING_OUTCOMES, UUID_ROW, tenantId, Timestamp.from(before), limit);
    }

    @Override
    public List<UUID> pendingAudit(UUID tenantId, Instant before, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(before, "before");
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query(PENDING_AUDIT, UUID_ROW, tenantId, Timestamp.from(before), limit);
    }

    @Override
    public List<UUID> pendingApprovals(UUID tenantId, Instant before, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(before, "before");
        if (limit < 1) {
            return List.of();
        }
        return jdbc.query(PENDING_APPROVALS, UUID_ROW, tenantId, Timestamp.from(before), limit);
    }

    @Override
    public LedgerAudit audit(String partition) {
        Objects.requireNonNull(partition, "partition");
        Long rowCount =
                jdbc.queryForObject(
                        "select count(*) from agent_decision_record where partition = ?",
                        Long.class,
                        partition);
        Long headSequence =
                jdbc.queryForObject(
                        "select last_sequence from agent_decision_chain where partition = ?",
                        Long.class,
                        partition);
        return new LedgerAudit(
                partition,
                rowCount == null ? 0 : rowCount,
                headSequence == null ? 0 : headSequence,
                verify(partition));
    }

    /**
     * 校对分区：<b>先读链头，再按链头截断扫记录</b>。
     *
     * <p>记录与链头在同一个追加事务里提交，所以「读到的链头是第 N 条」意味着记录 1..N 都已提交；之后才提交的 N+1 被 {@code sequence <= N}
     * 排除，不会因为两次读之间有人追加而误报篡改。反过来，记录数少于 N 或者末条 {@code record_hash} 对不上链头，就是尾部被截断或改过——行级触发器挡不住 {@code
     * TRUNCATE}，这一条比对是必需的。
     */
    @Override
    public LedgerVerification verify(String partition) {
        Objects.requireNonNull(partition, "partition");
        ChainHead head = readChainHead(partition);
        if (head == null) {
            return LedgerVerification.ok(partition, 0);
        }
        List<DecisionRecord> records =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM agent_decision_record WHERE partition = ? AND sequence <= ?"
                                + " ORDER BY sequence",
                        RECORD_MAPPER,
                        partition,
                        head.lastSequence());
        String expectedPrev = LedgerHash.GENESIS;
        long checked = 0;
        for (DecisionRecord record : records) {
            checked++;
            if (!record.prevHash().equals(expectedPrev)) {
                return LedgerVerification.mismatch(
                        partition, checked, record.recordId(), expectedPrev, record.prevHash());
            }
            String expectedHash = LedgerHash.of(record);
            if (!expectedHash.equals(record.recordHash())) {
                return LedgerVerification.mismatch(
                        partition, checked, record.recordId(), expectedHash, record.recordHash());
            }
            expectedPrev = record.recordHash();
        }
        DecisionRecord last = records.isEmpty() ? null : records.get(records.size() - 1);
        String lastHash = last == null ? LedgerHash.GENESIS : last.recordHash();
        if (checked != head.lastSequence() || !head.lastHash().equals(lastHash)) {
            return LedgerVerification.mismatch(
                    partition,
                    checked,
                    last == null ? null : last.recordId(),
                    head.lastHash(),
                    lastHash);
        }
        return LedgerVerification.ok(partition, checked);
    }

    /** 读分区链头；分区还没写过时返回 {@code null}。 */
    private ChainHead readChainHead(String partition) {
        List<ChainHead> heads =
                jdbc.query(
                        "SELECT last_sequence, last_hash FROM agent_decision_chain WHERE partition = ?",
                        (rs, rowNum) ->
                                new ChainHead(
                                        rs.getLong("last_sequence"), rs.getString("last_hash")),
                        partition);
        return heads.isEmpty() ? null : heads.get(0);
    }

    /** 单个追加事务：锁链头、算序号与哈希、插入记录、推进链头。 */
    private DecisionRecord inTransaction(
            UUID tenantId, UUID decisionId, DecisionRecordKind kind, Map<String, Object> body) {
        String partition = DecisionRecord.partitionOf(tenantId);
        DecisionRecord appended =
                transaction.execute(
                        status -> {
                            DecisionRecord raced =
                                    idempotent(kind) ? existing(decisionId, kind) : null;
                            if (raced != null) {
                                return raced;
                            }
                            jdbc.update(ENSURE_CHAIN, partition, LedgerHash.GENESIS);
                            List<ChainHead> heads =
                                    jdbc.query(
                                            LOCK_CHAIN,
                                            (rs, rowNum) ->
                                                    new ChainHead(
                                                            rs.getLong("last_sequence"),
                                                            rs.getString("last_hash")),
                                            partition);
                            if (heads.isEmpty()) {
                                throw new IllegalStateException(
                                        "agent_decision_chain is missing for " + partition);
                            }
                            ChainHead head = heads.get(0);
                            long sequence = head.lastSequence() + 1;
                            Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                            DecisionRecord unsigned =
                                    new DecisionRecord(
                                            UUID.randomUUID(),
                                            decisionId,
                                            kind,
                                            partition,
                                            sequence,
                                            head.lastHash(),
                                            LedgerHash.GENESIS,
                                            recordedAt,
                                            tenantId,
                                            new LinkedHashMap<>(body));
                            String recordHash = LedgerHash.of(unsigned);
                            jdbc.update(
                                    INSERT_RECORD,
                                    unsigned.recordId(),
                                    decisionId,
                                    kind.wire(),
                                    partition,
                                    sequence,
                                    head.lastHash(),
                                    recordHash,
                                    OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC),
                                    tenantId,
                                    DecisionRecordBodies.SCHEMA,
                                    JSON.writeValueAsString(body));
                            jdbc.update(ADVANCE_CHAIN, sequence, recordHash, partition);
                            return new DecisionRecord(
                                    unsigned.recordId(),
                                    decisionId,
                                    kind,
                                    partition,
                                    sequence,
                                    head.lastHash(),
                                    recordHash,
                                    recordedAt,
                                    tenantId,
                                    new LinkedHashMap<>(body));
                        });
        if (appended == null) {
            throw new IllegalStateException("append produced no record for decision " + decisionId);
        }
        return appended;
    }

    private static boolean idempotent(DecisionRecordKind kind) {
        return kind == DecisionRecordKind.INTENT || kind == DecisionRecordKind.OUTCOME;
    }

    /** 按 {@code (decision_id, record_kind)} 读已有记录；没有返回 {@code null}。 */
    private DecisionRecord existing(UUID decisionId, DecisionRecordKind kind) {
        List<DecisionRecord> rows =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM agent_decision_record WHERE decision_id = ?"
                                + " AND record_kind = ? ORDER BY sequence LIMIT 1",
                        RECORD_MAPPER,
                        decisionId,
                        kind.wire());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 并发下先查后插撞上 unique index：再查一次，仍未命中原样抛出。 */
    private DecisionRecord existingAfterRace(
            UUID decisionId, DecisionRecordKind kind, DuplicateKeyException cause) {
        DecisionRecord raced = existing(decisionId, kind);
        if (raced == null) {
            throw cause;
        }
        return raced;
    }

    /**
     * 同一 {@code decision_id} 只属于一个租户：租户相同则幂等返回首条，不同则 fail-closed 拒绝。
     *
     * <p>{@code partial unique index} 按 {@code decision_id} 全库唯一，所以跨租户复用必然撞上别人的记录；把它返回给
     * 调用方就是跨租户数据泄露，与 {@code find} / {@code byDecision} 的租户作用域自相矛盾。
     *
     * <p>追加路径的三个出口——外层预查命中、事务内查到的既有记录（READ COMMITTED 下能看到并发事务刚提交的行）、 {@code unique index}
     * 冲突后的回查——全部经本方法过一道，少一处就会把别人的记录当幂等结果返回。
     */
    private static DecisionRecord existingForTenant(DecisionRecord existing, UUID tenantId) {
        if (!existing.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "decision_id belongs to another tenant: " + existing.decisionId());
        }
        return existing;
    }

    /** 非 intent 记录的租户由已落账的 intent 反查。 */
    private UUID requireTenant(UUID decisionId) {
        List<UUID> tenants =
                jdbc.query(
                        "SELECT tenant_id FROM agent_decision_record"
                                + " WHERE decision_id = ? AND record_kind = ?",
                        (rs, rowNum) -> rs.getObject("tenant_id", UUID.class),
                        decisionId,
                        DecisionRecordKind.INTENT.wire());
        if (tenants.isEmpty()) {
            throw new IllegalArgumentException("unknown decision: " + decisionId);
        }
        return tenants.get(0);
    }

    private static Map<String, Object> readBody(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(JSON.readValue(json, BODY));
    }

    /** 链头的一行：当前序号与当前哈希。 */
    private record ChainHead(long lastSequence, String lastHash) {}
}
