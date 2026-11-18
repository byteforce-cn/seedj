package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.LedgerHash;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * <p>追加顺序：建链头（{@code on conflict do nothing}）→ {@code select ... for update} 锁本分区 → 取
 * {@code sequence = last_sequence + 1} 与 {@code prev_hash = last_hash} → 组装记录体 → 算
 * {@link LedgerHash} → 插记录 → 推进链头。行锁让同分区的追加串行，不同分区互不阻塞。
 *
 * <p>幂等：{@code intent} 与 {@code outcome} 每个 decision 至多一条，先查后插；并发下先查后插会撞车， 最终由 partial unique
 * index 兜底——插入冲突时再查一次，返回已存在的那条。{@code approval} 与 {@code reconciled} 不幂等： 前者是每次观测一条审批事实，后者是每次核对一条观察。
 *
 * <p>记录体（{@code body}）的组装口径写在 {@link DecisionLedger} 的类注释里，内存实现必须逐字对齐—— {@code record_hash}
 * 覆盖整个记录体，两套实现写出的记录体不同，同一份决策在两套账本里的哈希就不同。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresDecisionLedger implements DecisionLedger {

    /** 记录体与 {@code schema_version} 列共用的 schema 名。 */
    static final String SCHEMA = "agent.decision.v1";

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
            return existing;
        }
        try {
            return inTransaction(
                    intent.tenantId(),
                    intent.decisionId(),
                    DecisionRecordKind.INTENT,
                    intentBody(intent));
        } catch (DuplicateKeyException e) {
            return existingAfterRace(intent.decisionId(), DecisionRecordKind.INTENT, e);
        }
    }

    @Override
    public DecisionRecord appendApproval(UUID decisionId, ApprovalSnapshot snapshot) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(snapshot, "snapshot");
        UUID tenantId = requireTenant(decisionId);
        return inTransaction(
                tenantId, decisionId, DecisionRecordKind.APPROVAL, approvalBody(snapshot));
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
            return existing;
        }
        try {
            return inTransaction(
                    tenantId, decisionId, DecisionRecordKind.OUTCOME, outcomeBody(effect, source));
        } catch (DuplicateKeyException e) {
            return existingAfterRace(decisionId, DecisionRecordKind.OUTCOME, e);
        }
    }

    @Override
    public DecisionRecord appendReconciled(UUID decisionId, DecisionEffect effect, String reason) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(reason, "reason");
        UUID tenantId = requireTenant(decisionId);
        DecisionRecord outcome = existing(decisionId, DecisionRecordKind.OUTCOME);
        Map<String, Object> body = reconciledBody(effect, reason, outcome);
        return inTransaction(tenantId, decisionId, DecisionRecordKind.RECONCILED, body);
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
    public LedgerVerification verify(String partition) {
        Objects.requireNonNull(partition, "partition");
        List<DecisionRecord> records =
                jdbc.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM agent_decision_record WHERE partition = ? ORDER BY sequence",
                        RECORD_MAPPER,
                        partition);
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
        return LedgerVerification.ok(partition, checked);
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
                            Instant recordedAt =
                                    Instant.now().truncatedTo(ChronoUnit.MICROS);
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
                                    SCHEMA,
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

    /** intent 记录体（{@link DecisionLedger} 类注释中的格式说明）。 */
    private static Map<String, Object> intentBody(DecisionIntent intent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.INTENT.wire());
        body.put("task_contract_id", intent.taskContractId().toString());
        body.put("conversation_id", intent.conversationId().toString());
        body.put("step", intent.step());
        body.put("kind", intent.kind().wire());
        if (intent.actionClass() != null) {
            body.put("action_class", intent.actionClass().wire());
        }
        body.put("command", commandBody(intent.command()));
        body.put("args_hash", intent.argsHash());
        body.put("idem_fingerprint", intent.idemFingerprint());
        if (!intent.args().isEmpty()) {
            body.put("args", new LinkedHashMap<>(intent.args()));
        }
        body.put("rationale", intent.rationale());
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Evidence item : intent.evidence()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "kind", item.kind());
            putIfPresent(entry, "ref", item.ref());
            putIfPresent(entry, "digest", item.digest());
            evidence.add(entry);
        }
        body.put("evidence", evidence);
        body.put("model", modelBody(intent.model()));
        if (intent.policy() != null) {
            body.put("policy", policyBody(intent.policy()));
        }
        body.put("identity", identityBody(intent.identity()));
        if (intent.approval() != null) {
            body.put("approval", approvalFields(intent.approval()));
        }
        return body;
    }

    private static Map<String, Object> approvalBody(ApprovalSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.APPROVAL.wire());
        body.put("approval", approvalFields(snapshot));
        return body;
    }

    private static Map<String, Object> outcomeBody(DecisionEffect effect, OutcomeSource source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.OUTCOME.wire());
        body.put("source", source.wire());
        body.put("effect", effectFields(effect));
        return body;
    }

    private static Map<String, Object> reconciledBody(
            DecisionEffect effect, String reason, DecisionRecord outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("record_kind", DecisionRecordKind.RECONCILED.wire());
        body.put("source", OutcomeSource.RECONCILED.wire());
        body.put("effect", effectFields(effect));
        body.put("reason", reason);
        if (outcome != null) {
            body.put("supersedes_record_id", outcome.recordId().toString());
        }
        return body;
    }

    private static Map<String, Object> effectFields(DecisionEffect effect) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", effect.status().wire());
        putIfPresent(fields, "external_ref", effect.externalRef());
        putIfPresent(fields, "audit_entry_id", effect.auditEntryId());
        putIfPresent(fields, "command_id", stringOf(effect.commandId()));
        putIfPresent(fields, "error_code", effect.errorCode());
        putIfPresent(fields, "completed_at", stringOf(effect.completedAt()));
        return fields;
    }

    private static Map<String, Object> commandBody(CommandRef command) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", command.type());
        fields.put("targets", command.targets().stream().map(UUID::toString).toList());
        putIfPresent(fields, "expectedVersion", command.expectedVersion());
        return fields;
    }

    private static Map<String, Object> modelBody(ModelRef model) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", model.name());
        fields.put("version", model.version());
        putIfPresent(fields, "prompt_ver", model.promptVer());
        putIfPresent(fields, "params_digest", model.paramsDigest());
        return fields;
    }

    private static Map<String, Object> policyBody(PolicyRef policy) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("engine", policy.engine());
        putIfPresent(fields, "bundle", policy.bundle());
        putIfPresent(fields, "bundle_version", policy.bundleVersion());
        putIfPresent(fields, "epoch", policy.epoch());
        putIfPresent(fields, "verdict", policy.verdict());
        fields.put("rule_ids", List.copyOf(policy.ruleIds()));
        putIfPresent(fields, "explain_ref", policy.explainRef());
        return fields;
    }

    private static Map<String, Object> identityBody(AgentIdentity identity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor", identity.actor().toString());
        putIfPresent(fields, "on_behalf_of", stringOf(identity.onBehalfOf()));
        putIfPresent(fields, "proxy_binding_id", stringOf(identity.proxyBindingId()));
        putIfPresent(fields, "acting_service", identity.actingService());
        return fields;
    }

    private static Map<String, Object> approvalFields(ApprovalSnapshot snapshot) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("instance_id", snapshot.instanceId());
        fields.put("task_id", snapshot.taskId());
        fields.put("status", snapshot.status().wire());
        putIfPresent(fields, "requested_at", stringOf(snapshot.requestedAt()));
        putIfPresent(fields, "approver", stringOf(snapshot.approver()));
        putIfPresent(fields, "approved_at", stringOf(snapshot.approvedAt()));
        fields.put("args_hash", snapshot.argsHash());
        putIfPresent(fields, "instance_revision", snapshot.instanceRevision());
        return fields;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
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
