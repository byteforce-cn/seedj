package cn.byteforce.seedj.infra.jdbc.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.LedgerHash;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.DecisionLedgerContractTest;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 决策账本：公共契约 + 触发器级只追加 + 篡改检测。
 *
 * <p>契约部分继承 {@link DecisionLedgerContractTest}；本类另外验证只有数据库才能提供的保证：账本表上的
 * {@code before update or delete} 触发器真的拒绝改写，以及 {@code verify} 能发现被直接写入的错误哈希。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Testcontainers
class PostgresDecisionLedgerTest extends DecisionLedgerContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    private static DecisionLedger ledger;

    @BeforeAll
    static void initialize() {
        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new AiSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        ledger = new PostgresDecisionLedger(dataSource);
    }

    @Override
    protected DecisionLedger ledger() {
        return ledger;
    }

    @Override
    protected void reset() {
        jdbc.execute("TRUNCATE agent_decision_record, agent_decision_chain");
    }

    @Test
    @DisplayName("UPDATE 被触发器拒绝：账本表只追加")
    void update_is_rejected_by_trigger() {
        DecisionRecord record = ledger().appendIntent(intent(TENANT, decision(21)));

        // 触发器用 RAISE EXCEPTION（SQLState P0001），Spring 翻译成 UncategorizedSQLException。
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE agent_decision_record SET body = body"
                                                + " WHERE record_id = ?",
                                        record.recordId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("DELETE 被触发器拒绝：账本表只追加")
    void delete_is_rejected_by_trigger() {
        DecisionRecord record = ledger().appendIntent(intent(TENANT, decision(22)));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM agent_decision_record WHERE record_id = ?",
                                        record.recordId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("verify 发现被直连写入的错误 record_hash：ok=false 并指向该记录")
    void verify_detects_tampered_hash() {        UUID tenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        String partition = DecisionRecord.partitionOf(tenant);
        UUID recordId = UUID.randomUUID();
        String forgedHash = "f".repeat(64);
        jdbc.update(
                """
                INSERT INTO agent_decision_chain (partition, last_sequence, last_hash)
                VALUES (?, 0, ?) ON CONFLICT (partition) DO NOTHING
                """,
                partition,
                LedgerHash.GENESIS);
        jdbc.update(
                """
                INSERT INTO agent_decision_record
                    (record_id, decision_id, record_kind, partition, sequence, prev_hash,
                     record_hash, recorded_at, tenant_id, schema_version, body)
                VALUES (?, ?, 'intent', ?, 1, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                recordId,
                decision(23),
                partition,
                LedgerHash.GENESIS,
                forgedHash,
                OffsetDateTime.ofInstant(
                        Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC),
                tenant,
                "agent.decision.v1",
                "{\"schema\":\"agent.decision.v1\",\"record_kind\":\"intent\"}");

        LedgerVerification verification = ledger().verify(partition);

        assertThat(verification.ok()).isFalse();
        assertThat(verification.failedRecordId()).isEqualTo(recordId);
        assertThat(verification.actualHash()).isEqualTo(forgedHash);
        assertThat(verification.checkedRecords()).isEqualTo(1L);
    }

    @Test
    @DisplayName("verify 发现链断：prev_hash 不接前一条 record_hash")
    void verify_detects_broken_chain() {
        UUID tenant = UUID.fromString("88888888-8888-8888-8888-888888888888");
        String partition = DecisionRecord.partitionOf(tenant);
        UUID recordId = UUID.randomUUID();
        UUID decisionId = decision(24);
        Instant recordedAt = Instant.parse("2026-11-18T00:00:00Z");
        DecisionRecord forged =
                new DecisionRecord(
                        recordId,
                        decisionId,
                        DecisionRecordKind.INTENT,
                        partition,
                        1L,
                        "f".repeat(64),
                        LedgerHash.GENESIS,
                        recordedAt,
                        tenant,
                        Map.of("schema", "agent.decision.v1"));
        String selfConsistentHash = LedgerHash.of(forged);
        jdbc.update(
                """
                INSERT INTO agent_decision_chain (partition, last_sequence, last_hash)
                VALUES (?, 0, ?) ON CONFLICT (partition) DO NOTHING
                """,
                partition,
                LedgerHash.GENESIS);
        jdbc.update(
                """
                INSERT INTO agent_decision_record
                    (record_id, decision_id, record_kind, partition, sequence, prev_hash,
                     record_hash, recorded_at, tenant_id, schema_version, body)
                VALUES (?, ?, 'intent', ?, 1, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                recordId,
                decisionId,
                partition,
                forged.prevHash(),
                selfConsistentHash,
                OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC),
                tenant,
                "agent.decision.v1",
                "{\"schema\":\"agent.decision.v1\"}");

        LedgerVerification verification = ledger().verify(partition);

        assertThat(verification.ok()).isFalse();
        assertThat(verification.failedRecordId()).isEqualTo(recordId);
        assertThat(verification.expectedHash()).isEqualTo(LedgerHash.GENESIS);
        assertThat(verification.actualHash()).isEqualTo(forged.prevHash());
    }

    @Test
    @DisplayName("intent 记录体按文档格式组装，且 jsonb 往返后内容不变")
    void intent_body_follows_documented_shape() {
        DecisionIntent intent = intent(TENANT, decision(25));

        DecisionRecord appended = ledger().appendIntent(intent);
        DecisionRecord reloaded = ledger().byDecision(TENANT, intent.decisionId()).get(0);

        assertThat(appended.body())
                .containsEntry("schema", "agent.decision.v1")
                .containsEntry("record_kind", "intent")
                .containsEntry("kind", "execute_tool")
                .containsEntry("action_class", "reversible")
                .containsEntry("step", 0)
                .containsEntry("rationale", "用户要求创建工单")
                .containsEntry("args_hash", "sha256:" + "a".repeat(64))
                .containsEntry("idem_fingerprint", "b".repeat(64));
        assertThat(appended.body().get("command"))
                .isEqualTo(
                        Map.of(
                                "type",
                                "ticket.create",
                                "targets",
                                List.of("77777777-7777-7777-7777-777777777777"),
                                "expectedVersion",
                                7L));
        // jsonb 往返把整数值还原成 Integer（内存里是 Long），但规范 JSON 文本不变，哈希与 verify 均不受影响。
        assertThat(reloaded.body())
                .containsEntry("record_kind", "intent")
                .containsEntry("step", 0)
                .containsEntry("idem_fingerprint", "b".repeat(64));
        assertThat(reloaded.recordHash()).isEqualTo(appended.recordHash());
        assertThat(ledger().verify(appended.partition()).ok()).isTrue();
    }

    @Test
    @DisplayName("不需要动作分类的步骤：省略 action_class/policy/args")
    void intent_without_optional_fields_omits_them() {
        UUID decisionId = decision(26);

        DecisionRecord record = ledger().appendIntent(askUserIntent(TENANT, decisionId));

        assertThat(record.body())
                .containsEntry("kind", "ask_user")
                .doesNotContainKeys("action_class", "policy", "args", "approval");
    }

    @Test
    @DisplayName("approval 记录：租户由 intent 反查，记录体带审批快照")
    void approval_is_appended_with_resolved_tenant() {
        DecisionIntent intent = intent(TENANT, decision(27));
        ledger().appendIntent(intent);

        DecisionRecord approval = ledger().appendApproval(intent.decisionId(), snapshot());

        assertThat(approval.kind()).isEqualTo(DecisionRecordKind.APPROVAL);
        assertThat(approval.tenantId()).isEqualTo(TENANT);
        assertThat(approval.sequence()).isEqualTo(2L);
        assertThat(approval.body()).containsEntry("record_kind", "approval");
        assertThat(approval.body().get("approval"))
                .isEqualTo(
                        Map.of(
                                "instance_id",
                                "wf-instance",
                                "task_id",
                                "wf-task",
                                "status",
                                "approved",
                                "requested_at",
                                Instant.parse("2026-11-18T00:00:00Z").toString(),
                                "approver",
                                "33333333-3333-3333-3333-333333333333",
                                "approved_at",
                                Instant.parse("2026-11-18T00:00:00Z").toString(),
                                "args_hash",
                                "sha256:" + "a".repeat(64),
                                "instance_revision",
                                1L));
        assertThat(ledger().verify(approval.partition()).ok()).isTrue();
    }

    @Test
    @DisplayName("需要审批的 intent：记录体带 action_class 与审批快照")
    void intent_requiring_approval_carries_snapshot() {
        UUID decisionId = decision(28);
        DecisionIntent intent =
                new DecisionIntent(
                        decisionId,
                        TENANT,
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        UUID.fromString("66666666-6666-6666-6666-666666666666"),
                        1,
                        DecisionKind.EXECUTE_TOOL,
                        ActionClass.IRREVERSIBLE_FINANCIAL,
                        new CommandRef("payment.refund", List.of(), null),
                        "sha256:" + "a".repeat(64),
                        "b".repeat(64),
                        null,
                        "用户要求退款",
                        null,
                        new ModelRef("seedj-agent", "2026-11", null, null),
                        null,
                        new AgentIdentity(
                                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                                null,
                                null,
                                null),
                        snapshot());

        DecisionRecord record = ledger().appendIntent(intent);

        assertThat(record.body())
                .containsEntry("action_class", "irreversible_financial")
                .doesNotContainKeys("policy", "args");
        assertThat(record.body().get("approval"))
                .isEqualTo(
                        Map.of(
                                "instance_id",
                                "wf-instance",
                                "task_id",
                                "wf-task",
                                "status",
                                "approved",
                                "requested_at",
                                Instant.parse("2026-11-18T00:00:00Z").toString(),
                                "approver",
                                "33333333-3333-3333-3333-333333333333",
                                "approved_at",
                                Instant.parse("2026-11-18T00:00:00Z").toString(),
                                "args_hash",
                                "sha256:" + "a".repeat(64),
                                "instance_revision",
                                1L));
    }

    @Test
    @DisplayName("reconciled 记录：带核对依据，并引用被修正的 outcome")
    void reconciled_links_back_to_outcome() {
        DecisionIntent intent = intent(TENANT, decision(29));
        ledger().appendIntent(intent);
        DecisionRecord outcome =
                ledger().appendOutcome(
                        intent.decisionId(), DecisionEffect.unknown(), OutcomeSource.RUNTIME_REPORTED);

        DecisionRecord reconciled =
                ledger().appendReconciled(
                        intent.decisionId(), DecisionEffect.ok("audit-9", null), "与外部系统对齐");

        assertThat(reconciled.kind()).isEqualTo(DecisionRecordKind.RECONCILED);
        assertThat(reconciled.body())
                .containsEntry("record_kind", "reconciled")
                .containsEntry("source", "reconciled")
                .containsEntry("reason", "与外部系统对齐")
                .containsEntry("supersedes_record_id", outcome.recordId().toString());
        assertThat(reconciled.body().get("effect"))
                .isEqualTo(Map.of("status", "ok", "audit_entry_id", "audit-9"));
        assertThat(ledger().verify(reconciled.partition()).ok()).isTrue();
    }

    @Test
    @DisplayName("没有 outcome 时的 reconciled：省略 supersedes_record_id")
    void reconciled_without_outcome_omits_supersedes() {
        DecisionIntent intent = intent(TENANT, decision(30));
        ledger().appendIntent(intent);

        DecisionRecord reconciled =
                ledger().appendReconciled(
                        intent.decisionId(), DecisionEffect.unknown(), "执行中崩溃，待核对");

        assertThat(reconciled.body()).doesNotContainKey("supersedes_record_id");
    }

    private static ApprovalSnapshot snapshot() {
        Instant at = Instant.parse("2026-11-18T00:00:00Z");
        return new ApprovalSnapshot(
                "wf-instance",
                "wf-task",
                ApprovalStatus.APPROVED,
                at,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                at,
                "sha256:" + "a".repeat(64),
                1L);
    }

    private static DecisionIntent askUserIntent(UUID tenantId, UUID decisionId) {
        return new DecisionIntent(
                decisionId,
                tenantId,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                2,
                DecisionKind.ASK_USER,
                null,
                new CommandRef("ticket.query", List.of(), null),
                "sha256:" + "a".repeat(64),
                "b".repeat(64),
                null,
                "需要用户补充信息",
                null,
                new ModelRef("seedj-agent", "2026-11", null, null),
                null,
                new AgentIdentity(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"), null, null, null),
                null);
    }
}
