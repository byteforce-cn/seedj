package cn.byteforce.seedj.infra.jdbc.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.LedgerHash;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.DecisionLedgerContractTest;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * PostgreSQL 决策账本：公共契约 + 只有数据库才能提供的保证。
 *
 * <p>契约部分继承 {@link DecisionLedgerContractTest}；本类另外验证触发器级只追加、{@code verify} 能发现被直连
 * 篡改/截断的链，以及同一条 intent 并发落地时只留一条记录。
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
    void verify_detects_tampered_hash() {
        UUID tenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        String partition = DecisionRecord.partitionOf(tenant);
        UUID recordId = UUID.randomUUID();
        String forgedHash = "f".repeat(64);
        ensureChain(partition);
        insertRecord(recordId, decision(23), partition, LedgerHash.GENESIS, forgedHash, tenant);

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
        ensureChain(partition);
        insertRecord(
                recordId,
                decisionId,
                partition,
                forged.prevHash(),
                LedgerHash.of(forged),
                tenant);

        LedgerVerification verification = ledger().verify(partition);

        assertThat(verification.ok()).isFalse();
        assertThat(verification.failedRecordId()).isEqualTo(recordId);
        assertThat(verification.expectedHash()).isEqualTo(LedgerHash.GENESIS);
        assertThat(verification.actualHash()).isEqualTo(forged.prevHash());
    }

    @Test
    @DisplayName("verify 发现被截断的尾部：链头仍记着更多记录（TRUNCATE 绕过行触发器）")
    void verify_detects_truncated_tail() {
        ledger().appendIntent(intent(TENANT, decision(31)));
        DecisionRecord last = ledger().appendIntent(intent(TENANT, decision(32)));
        String partition = DecisionRecord.partitionOf(TENANT);

        jdbc.execute("TRUNCATE agent_decision_record");

        LedgerVerification verification = ledger().verify(partition);

        assertThat(verification.ok()).isFalse();
        assertThat(verification.checkedRecords()).isZero();
        assertThat(verification.failedRecordId()).isNull();
        assertThat(verification.expectedHash()).isEqualTo(last.recordHash());
        assertThat(verification.actualHash()).isEqualTo(LedgerHash.GENESIS);
    }

    @Test
    @DisplayName("同一个 intent 并发 append：只落一条，两个调用拿到同一 record_id")
    void concurrent_same_intent_appends_yield_one_record() throws Exception {
        for (int round = 1; round <= 5; round++) {
            DecisionIntent intent = intent(TENANT, decision(40 + round));

            List<DecisionRecord> results = appendSameConcurrently(intent);

            assertThat(results)
                    .extracting(DecisionRecord::recordId)
                    .containsOnly(results.get(0).recordId());
            assertThat(ledger().byDecision(TENANT, intent.decisionId())).hasSize(1);
            assertThat(ledger().verify(DecisionRecord.partitionOf(TENANT)).ok()).isTrue();
        }
    }

    /** 两个线程同时 append 同一条 intent。 */
    private List<DecisionRecord> appendSameConcurrently(DecisionIntent intent) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<DecisionRecord>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return ledger().appendIntent(intent);
                                }));
            }
            start.countDown();
            List<DecisionRecord> records = new ArrayList<>();
            for (Future<DecisionRecord> future : futures) {
                records.add(future.get(30, TimeUnit.SECONDS));
            }
            return records;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void ensureChain(String partition) {
        jdbc.update(
                """
                INSERT INTO agent_decision_chain (partition, last_sequence, last_hash)
                VALUES (?, 0, ?) ON CONFLICT (partition) DO NOTHING
                """,
                partition,
                LedgerHash.GENESIS);
    }

    /** 直连数据库写一条记录，绕过账本的哈希与链头维护。 */
    private static void insertRecord(
            UUID recordId,
            UUID decisionId,
            String partition,
            String prevHash,
            String recordHash,
            UUID tenant) {
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
                prevHash,
                recordHash,
                OffsetDateTime.ofInstant(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC),
                tenant,
                "agent.decision.v1",
                "{\"schema\":\"agent.decision.v1\",\"record_kind\":\"intent\"}");
    }
}
