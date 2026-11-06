package cn.byteforce.seedj.audit.jdbc;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditChain;
import cn.byteforce.seedj.audit.AuditEntry;
import cn.byteforce.seedj.audit.AuditEvent;
import cn.byteforce.seedj.audit.AuditPayloadDigest;
import cn.byteforce.seedj.audit.AuditVerifier;
import cn.byteforce.seedj.audit.CanonicalHasher;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计链在真实 PostgreSQL 上的事务与连续性契约。
 */
@Testcontainers
class JdbcAuditStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transaction;
    private static JdbcAuditStore store;
    private static AuditChain<String> chain;

    private static final AtomicLong IDS = new AtomicLong();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        new AuditSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcAuditStore(jdbcTemplate);
        chain = new AuditChain<>(store, Clocks.system(), head -> {
        }, () -> "audit-" + IDS.incrementAndGet());
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE audit_entry");
        jdbcTemplate.update("UPDATE audit_head SET sequence = 0, last_hash = ?",
                CanonicalHasher.GENESIS_PREV_HASH);
    }

    @Test
    @DisplayName("事务提交后条目落库、序号推进、链校验通过")
    void appendCommits() {
        transaction.executeWithoutResult(status -> chain.append(request("e-1")));

        List<AuditEntry<String>> entries = store.loadAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSequence()).isEqualTo(1);
        assertThat(store.head().getSequence()).isEqualTo(1);
        assertThat(store.head().getHash()).isEqualTo(entries.get(0).getHash());
        assertThat(chain.verify()).isEmpty();
    }

    @Test
    @DisplayName("回滚后没有条目、序号不消耗、链头不动")
    void rollbackLeavesNoTrace() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            chain.append(request("e-2"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.loadAll()).isEmpty();
        assertThat(store.head().getSequence()).isZero();
        assertThat(store.head().getHash()).isEqualTo(CanonicalHasher.GENESIS_PREV_HASH);
    }

    @Test
    @DisplayName("连续 append 序号连续、前序哈希串成链")
    void sequenceIsContinuous() {
        transaction.executeWithoutResult(status -> chain.append(request("e-3")));
        transaction.executeWithoutResult(status -> chain.append(request("e-4")));

        List<AuditEntry<String>> entries = store.loadAll();
        assertThat(entries).extracting(AuditEntry::getSequence).containsExactly(1L, 2L);
        assertThat(entries.get(1).getPrevHash()).isEqualTo(entries.get(0).getHash());
        assertThat(chain.verify()).isEmpty();
    }

    @Test
    @DisplayName("v1.1 追加落库 payload_digest，事件导出可复算")
    void v11AppendPersistsDigestAndVerifiesWithEvents() {
        AuditEvent event = event("018f2e3a-4000-8000-0000-0000000000e1", "Alice");

        transaction.executeWithoutResult(status -> chain.append(withEvents("e-5", List.of(event))));

        AuditEntry<String> entry = store.loadAll().get(0);
        assertThat(entry.getPayloadDigest()).isEqualTo(AuditPayloadDigest.compute(List.of(event)));
        assertThat(AuditVerifier.verify(store.loadAll(), Map.of(event.id(), event))).isEmpty();
    }

    @Test
    @DisplayName("事件正文篡改在 JDBC 回读后仍被检出")
    void tamperedEventPayloadIsDetectedByJdbcReadBack() {
        AuditEvent event = event("018f2e3a-4000-8000-0000-0000000000e2", "Alice");
        transaction.executeWithoutResult(status -> chain.append(withEvents("e-6", List.of(event))));

        AuditEvent tampered = event(event.id().toString(), "Mallory");

        assertThat(AuditVerifier.verify(store.loadAll(), Map.of(tampered.id(), tampered)))
                .extracting(AuditVerifier.AuditViolation::getType)
                .contains("payload_digest_mismatch");
    }

    @Test
    @DisplayName("append-only 规则拦截 UPDATE / DELETE")
    void updateAndDeleteAreBlockedByAppendOnlyRules() {
        transaction.executeWithoutResult(status -> chain.append(request("e-7")));

        int updated = jdbcTemplate.update("UPDATE audit_entry SET action = 'tampered'");
        int deleted = jdbcTemplate.update("DELETE FROM audit_entry");

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(store.loadAll()).hasSize(1);
        assertThat(store.loadAll().get(0).getAction()).isEqualTo("employee.onboarded");
    }

    @Test
    @DisplayName("DDL 使用 timestamp / payload_digest 列并带长度约束")
    void ddlHasColumnAndChecks() {
        assertThat(column("timestamp")).isOne();
        assertThat(column("occurred_at")).isZero();
        assertThat(column("payload_digest")).isOne();
        assertThat(rule("audit_no_update")).isOne();
        assertThat(rule("audit_no_delete")).isOne();
        assertThat(constraint("audit_payload_digest_len")).isOne();
    }

    private static int column(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'audit_entry' AND column_name = ?",
                Integer.class, name);
        return count == null ? 0 : count;
    }

    private static int rule(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_rules WHERE tablename = 'audit_entry' AND rulename = ?",
                Integer.class, name);
        return count == null ? 0 : count;
    }

    private static int constraint(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                Integer.class, name);
        return count == null ? 0 : count;
    }

    private static AuditEvent event(String id, String name) {
        return new AuditEvent(UUID.fromString(id), "employee.onboarded",
                JSON.readTree("{\"name\":\"" + name + "\"}"));
    }

    private static AuditAppendRequest<String> withEvents(String resourceId,
                                                         List<AuditEvent> events) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("command_id", UUID.randomUUID().toString());
        var ids = payload.putArray("event_ids");
        events.forEach(event -> ids.add(event.id().toString()));
        return new AuditAppendRequest<>("employee.onboarded", "alice", "employee", resourceId,
                payload, events);
    }

    private static AuditAppendRequest<String> request(String resourceId) {
        return new AuditAppendRequest<>("employee.onboarded", "alice", "employee", resourceId,
                null);
    }
}
