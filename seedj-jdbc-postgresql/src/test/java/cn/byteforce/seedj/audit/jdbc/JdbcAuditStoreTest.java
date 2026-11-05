package cn.byteforce.seedj.audit.jdbc;

import cn.byteforce.seedj.audit.AuditAppendRequest;
import cn.byteforce.seedj.audit.AuditChain;
import cn.byteforce.seedj.audit.AuditEntry;
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

import java.util.List;
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

    private static AuditAppendRequest<String> request(String resourceId) {
        return new AuditAppendRequest<>("employee.onboarded", "alice", "employee", resourceId,
                null);
    }
}
