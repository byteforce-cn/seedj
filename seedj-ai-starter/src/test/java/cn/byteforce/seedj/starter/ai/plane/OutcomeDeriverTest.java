package cn.byteforce.seedj.starter.ai.plane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.EffectStatus;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.ai.AiSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.audit.AuditSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.cqrs.CommandSchemaCapability;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * outcome 上报与对账（spec §6.3 链上条目现状、§6.4 两段式效果与对账）。
 *
 * <p>两条断言贯穿全类：<b>上报不是结论</b>——runtime 只能报 {@code command_id}，结局一律由链上事实推导，
 * {@code audit_entry_id} 在上报阶段永远为空；<b>对账才补链校验</b>——{@code reconciled} 记录按
 * {@code audit_entry > command_record} 的关联补 {@code audit_entry_id}，绝不回填原 outcome。
 *
 * <p>业务库用真 PostgreSQL（{@code command_record} 与 {@code audit_entry} 的约束、jsonb 取值都在测试里落实），
 * 账本用 {@link InMemoryDecisionLedger}：这里验的是派生与对账规则，不是账本实现（那有各自的契约测试）。
 */
@Testcontainers
class OutcomeDeriverTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID CONTRACT = UUID.fromString("018f2e3a-1234-7000-8000-00000000c001");
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000e001");
    private static final UUID TARGET = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final String COMMAND_TYPE = "ticket.create";
    private static final String FINGERPRINT = "0f".repeat(32);
    private static final String OTHER_FINGERPRINT = "1a".repeat(32);
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final AtomicLong AUDIT_SEQUENCE = new AtomicLong();

    private static JdbcTemplate jdbc;

    private static DataSource dataSource;

    private InMemoryDecisionLedger ledger;

    private OutcomeDeriver deriver;

    private ReconciliationJob job;

    @BeforeAll
    static void initializeSchema() {
        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new AiSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        new CommandSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        new AuditSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table command_record");
        jdbc.execute("truncate table audit_entry");
        ledger = new InMemoryDecisionLedger();
        deriver = new OutcomeDeriver(dataSource, ledger, properties());
        job =
                new ReconciliationJob(
                        dataSource, ledger, new ConfiguredReconciliationSource(), properties());
    }

    @Test
    @DisplayName("上报的 outcome 不是链校验：OK 但 audit_entry_id 为空")
    void reported_outcome_is_not_chain_verified() {
        UUID decisionId = newDecision();
        UUID commandId = insertAccepted(decisionId, FINGERPRINT);
        insertAuditEntry(commandId);

        OutcomeDeriver.Reported reported = deriver.report(decisionId, commandId);

        assertThat(reported.decisionId()).isEqualTo(decisionId);
        assertThat(reported.status()).isEqualTo(EffectStatus.OK);
        assertThat(effectOf(outcomeOf(decisionId)))
                .containsEntry("status", "ok")
                .containsEntry("command_id", commandId.toString())
                .doesNotContainKey("audit_entry_id");
    }

    @Test
    @DisplayName("对账按链上条目补齐 audit_entry_id，原 outcome 不变")
    void reconcile_fills_audit_entry_id_from_chain() {
        UUID decisionId = newDecision();
        UUID commandId = insertAccepted(decisionId, FINGERPRINT);
        String auditEntryId = insertAuditEntry(commandId);
        deriver.report(decisionId, commandId);
        DecisionRecord outcome = outcomeOf(decisionId);

        Optional<DecisionRecord> reconciled = job.reconcile(decisionId);

        assertThat(reconciled).isPresent();
        DecisionRecord record = reconciled.get();
        assertThat(record.kind()).isEqualTo(DecisionRecordKind.RECONCILED);
        assertThat(record.body())
                .containsEntry("reason", "chain_verified")
                .containsEntry("supersedes_record_id", outcome.recordId().toString());
        assertThat(effectOf(record))
                .containsEntry("status", "ok")
                .containsEntry("audit_entry_id", auditEntryId)
                .containsEntry("command_id", commandId.toString());
        assertThat(effectOf(outcome)).doesNotContainKey("audit_entry_id");
    }

    @Test
    @DisplayName("说谎的上报被链上事实覆盖：command_id 取库里那条；链上没有就是 command_not_found")
    void lying_report_is_overridden_by_chain_facts() {
        UUID decisionId = newDecision();
        UUID commandId = insertAccepted(decisionId, FINGERPRINT);

        OutcomeDeriver.Reported reported = deriver.report(decisionId, UUID.randomUUID());

        assertThat(reported.status()).isEqualTo(EffectStatus.OK);
        assertThat(effectOf(outcomeOf(decisionId)))
                .containsEntry("command_id", commandId.toString());

        UUID withoutCommand = newDecision();

        OutcomeDeriver.Reported missing = deriver.report(withoutCommand, UUID.randomUUID());

        assertThat(missing.status()).isEqualTo(EffectStatus.FAILED);
        assertThat(effectOf(outcomeOf(withoutCommand)))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "command_not_found");
    }

    @Test
    @DisplayName("有意图无效果且过了 TTL 的决策才会被对账入口列出")
    void intent_without_outcome_after_ttl_is_listed() {
        UUID decisionId = newDecision();
        List<Instant> cutoffs = new ArrayList<>();
        List<UUID> scanned = new ArrayList<>();
        ReconciliationJob scanning =
                new ReconciliationJob(
                        dataSource,
                        ledger,
                        before -> {
                            cutoffs.add(before);
                            if (intentOf(decisionId).recordedAt().isBefore(before)) {
                                scanned.add(decisionId);
                                return List.of(decisionId);
                            }
                            return List.of();
                        },
                        properties());
        Instant now = Instant.now();

        assertThat(scanning.reconcileOnce(now)).isZero();
        assertThat(cutoffs).containsExactly(now.minus(TTL));
        assertThat(scanned).isEmpty();

        assertThat(scanning.reconcileOnce(now.plus(TTL).plusSeconds(60))).isEqualTo(1);
        assertThat(scanned).containsExactly(decisionId);
        assertThat(ledger.byDecision(TENANT, decisionId))
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT);
    }

    @Test
    @DisplayName("链上拒绝带业务错误码：failed + rejection_code")
    void rejected_command_is_failed_with_chain_code() {
        UUID decisionId = newDecision();
        insertRejected(decisionId, "FORBIDDEN");

        OutcomeDeriver.Reported reported = deriver.report(decisionId, UUID.randomUUID());

        assertThat(reported.status()).isEqualTo(EffectStatus.FAILED);
        assertThat(effectOf(outcomeOf(decisionId)))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "FORBIDDEN");
    }

    @Test
    @DisplayName("幂等指纹对不上：failed + fingerprint_mismatch")
    void fingerprint_mismatch_is_failed() {
        UUID decisionId = newDecision();
        insertAccepted(decisionId, OTHER_FINGERPRINT);

        OutcomeDeriver.Reported reported = deriver.report(decisionId, UUID.randomUUID());

        assertThat(reported.status()).isEqualTo(EffectStatus.FAILED);
        assertThat(effectOf(outcomeOf(decisionId)))
                .containsEntry("status", "failed")
                .containsEntry("error_code", "fingerprint_mismatch");
    }

    @Test
    @DisplayName("同一个决策上报两次只留一条 outcome")
    void report_is_idempotent() {
        UUID decisionId = newDecision();
        UUID commandId = insertAccepted(decisionId, FINGERPRINT);

        OutcomeDeriver.Reported first = deriver.report(decisionId, commandId);
        OutcomeDeriver.Reported second = deriver.report(decisionId, commandId);

        assertThat(second.outcomeRecordId()).isEqualTo(first.outcomeRecordId());
        assertThat(ledger.byDecision(TENANT, decisionId))
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
    }

    @Test
    @DisplayName("命令还在执行（pending）：unknown，不是成功")
    void pending_command_is_unknown() {
        UUID decisionId = newDecision();
        insertPending(decisionId);

        OutcomeDeriver.Reported reported = deriver.report(decisionId, UUID.randomUUID());

        assertThat(reported.status()).isEqualTo(EffectStatus.UNKNOWN);
        assertThat(effectOf(outcomeOf(decisionId)))
                .containsEntry("status", "unknown")
                .doesNotContainKey("error_code");
    }

    @Test
    @DisplayName("HTTP 面：只收 command_id，返回 snake_case 的 status，未知决策 404，缺字段 400")
    void outcome_endpoint_accepts_only_command_id() throws Exception {
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new DecisionController(decisions(), deriver))
                        .setControllerAdvice(new ContractErrorHandler())
                        .build();
        UUID decisionId = newDecision();
        UUID commandId = insertAccepted(decisionId, FINGERPRINT);

        mvc.perform(
                        post("/v1/decisions/" + decisionId + "/outcome")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"command_id\": \""
                                                + commandId
                                                + "\", \"status\": \"failed\","
                                                + " \"audit_entry_id\": \"audit-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision_id").value(decisionId.toString()))
                .andExpect(jsonPath("$.outcome_record_id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(
                        post("/v1/decisions/" + UUID.randomUUID() + "/outcome")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"command_id\": \"" + commandId + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(
                        post("/v1/decisions/" + decisionId + "/outcome")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value(ContractErrorCode.INVALID_PAYLOAD.getCode()));
    }

    /** 账本 fixture：一条 {@code execute_tool} 意图。 */
    private static DecisionIntent intent(UUID decisionId) {
        return new DecisionIntent(
                decisionId,
                TENANT,
                CONTRACT,
                CONVERSATION,
                0,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                new CommandRef(COMMAND_TYPE, List.of(TARGET), 7L),
                "sha256:args",
                FINGERPRINT,
                Map.of("amount", "128.00"),
                "为工单开票",
                List.of(),
                new ModelRef("gpt", "2026-11", null, null),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF_OF, null, "seedj-ai-plane"),
                null);
    }

    private UUID newDecision() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId));
        return decisionId;
    }

    private UUID insertAccepted(UUID decisionId, String fingerprint) {
        UUID commandId = UUID.randomUUID();
        jdbc.update(
                """
                insert into command_record (id, type, issued_at, issuer, payload, idempotency_key,
                    payload_fingerprint, status, event_ids, locator, version, created_at,
                    lease_until)
                values (?, ?, now(), ?, '{}'::jsonb, ?, ?, 'accepted', '[]'::jsonb, ?, 1, now(),
                    now() + interval '5 minutes')
                """,
                commandId,
                COMMAND_TYPE,
                ACTOR,
                decisionId.toString(),
                fingerprint,
                UUID.randomUUID());
        return commandId;
    }

    private void insertRejected(UUID decisionId, String rejectionCode) {
        jdbc.update(
                """
                insert into command_record (id, type, issued_at, issuer, payload, idempotency_key,
                    payload_fingerprint, status, rejection_code, rejection_reason, event_ids,
                    created_at, lease_until)
                values (?, ?, now(), ?, '{}'::jsonb, ?, ?, 'rejected', ?, '业务规则拒绝',
                    '[]'::jsonb, now(), now() + interval '5 minutes')
                """,
                UUID.randomUUID(),
                COMMAND_TYPE,
                ACTOR,
                decisionId.toString(),
                FINGERPRINT,
                rejectionCode);
    }

    private void insertPending(UUID decisionId) {
        jdbc.update(
                """
                insert into command_record (id, type, issued_at, issuer, payload, idempotency_key,
                    payload_fingerprint, status, event_ids, created_at, lease_until)
                values (?, ?, now(), ?, '{}'::jsonb, ?, ?, 'pending', '[]'::jsonb, now(),
                    now() + interval '5 minutes')
                """,
                UUID.randomUUID(),
                COMMAND_TYPE,
                ACTOR,
                decisionId.toString(),
                FINGERPRINT);
    }

    /** 链条目 fixture：现状只有 {@code {command_id, event_ids}}，U9 前只能按 command_id 关联。 */
    private String insertAuditEntry(UUID commandId) {
        String auditEntryId = "audit-" + AUDIT_SEQUENCE.incrementAndGet();
        jdbc.update(
                """
                insert into audit_entry (id, sequence, timestamp, actor, action, resource_type,
                    resource_id, payload, hash, prev_hash)
                values (?, ?, now(), ?, ?, 'ticket', ?, ?::jsonb, ?, ?)
                """,
                auditEntryId,
                AUDIT_SEQUENCE.get(),
                ACTOR.toString(),
                COMMAND_TYPE,
                TARGET.toString(),
                "{\"command_id\":\"" + commandId + "\",\"event_ids\":[]}",
                "a".repeat(64),
                "b".repeat(64));
        return auditEntryId;
    }

    private DecisionService decisions() {
        ToolRegistry tools =
                new ConfiguredToolRegistry(
                        List.of(new AgentToolSpec(COMMAND_TYPE, ActionClass.REVERSIBLE)));
        CapabilityRegistry capabilities =
                new CapabilityRegistry(
                        List.of(
                                new AiProperties.Contract(
                                        CONTRACT.toString(), List.of(COMMAND_TYPE))),
                        tools);
        return new DecisionService(
                capabilities,
                tools,
                ledger,
                claims -> "jws",
                UUID::randomUUID,
                () -> Instant.now(),
                properties());
    }

    private DecisionRecord outcomeOf(UUID decisionId) {
        return ledger.byDecision(TENANT, decisionId).stream()
                .filter(record -> record.kind() == DecisionRecordKind.OUTCOME)
                .findFirst()
                .orElseThrow();
    }

    private DecisionRecord intentOf(UUID decisionId) {
        return ledger.byDecision(TENANT, decisionId).stream()
                .filter(record -> record.kind() == DecisionRecordKind.INTENT)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectOf(DecisionRecord record) {
        return (Map<String, Object>) record.body().get("effect");
    }

    private static AiProperties properties() {
        return new AiProperties(
                true,
                new AiProperties.Plane(
                        true, TENANT, new AiProperties.Reconcile(null, false), TTL),
                null,
                null,
                new AiProperties.Identity(ACTOR.toString(), ON_BEHALF_OF.toString()),
                null,
                List.of(),
                List.of());
    }
}
