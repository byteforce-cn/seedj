package cn.byteforce.seedj.infra.jdbc.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.Migration;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.ClaimRequest;
import cn.byteforce.seedj.outbox.spi.ClaimedRecord;
import cn.byteforce.seedj.outbox.spi.InboxStatus;
import cn.byteforce.seedj.outbox.spi.InboxStore;
import cn.byteforce.seedj.outbox.spi.OutboxFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Inbox 幂等与 Outbox 死信状态。 */
@Testcontainers
class JdbcInboxStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj_inbox")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static InboxStore inbox;
    private static PostgresOutboxStore outbox;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new PostgresOutboxSchemaCapability(runner).initialize();
        runner.migrate(
                "inbox-test",
                List.of(
                        new Migration(
                                1,
                                "inbox message",
                                """
                CREATE TABLE IF NOT EXISTS inbox_message (
                    consumer_group  TEXT        NOT NULL,
                    event_id        UUID        NOT NULL,
                    status          TEXT        NOT NULL DEFAULT 'processing',
                    attempts        INTEGER     NOT NULL DEFAULT 0,
                    last_error      TEXT,
                    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                    done_at         TIMESTAMPTZ,
                    dead_at         TIMESTAMPTZ,
                    PRIMARY KEY (consumer_group, event_id)
                );
                """)));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        inbox = new JdbcInboxStore(jdbc);
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();
        outbox = new PostgresOutboxStore(jdbc, () -> "outbox-" + ids.incrementAndGet());
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE outbox, outbox_audit, inbox_message");
    }

    @Test
    @DisplayName("同组同事件重复 claim 返回 false")
    void duplicateClaimInSameGroupReturnsFalse() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim("group-a", eventId)).isTrue();
        assertThat(inbox.claim("group-a", eventId)).isFalse();
        assertThat(inbox.find("group-a", eventId))
                .get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(InboxStatus.PROCESSING));
    }

    @Test
    @DisplayName("不同消费组独立 claim")
    void differentGroupsClaimIndependently() {
        UUID eventId = UUID.randomUUID();

        assertThat(inbox.claim("group-a", eventId)).isTrue();
        assertThat(inbox.claim("group-b", eventId)).isTrue();
    }

    @Test
    @DisplayName("done 后不能再 claim")
    void doneCannotBeClaimedAgain() {
        UUID eventId = UUID.randomUUID();
        inbox.claim("group-a", eventId);
        inbox.markDone("group-a", eventId);

        assertThat(inbox.claim("group-a", eventId)).isFalse();
        assertThat(inbox.find("group-a", eventId))
                .get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(InboxStatus.DONE));
    }

    @Test
    @DisplayName("dead 需要显式 reset")
    void deadNeedsExplicitReset() {
        UUID eventId = UUID.randomUUID();
        inbox.claim("group-a", eventId);
        inbox.markDead("group-a", eventId, "boom");

        assertThat(inbox.claim("group-a", eventId)).isFalse();
        assertThat(inbox.find("group-a", eventId))
                .get()
                .satisfies(
                        record -> {
                            assertThat(record.status()).isEqualTo(InboxStatus.DEAD);
                            assertThat(record.lastError()).isEqualTo("boom");
                        });
        assertThat(inbox.resetDead("group-a", eventId)).isTrue();
        assertThat(inbox.find("group-a", eventId))
                .get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(InboxStatus.PROCESSING));
    }

    @Test
    @DisplayName("Outbox 不可重试失败进入 DEAD，retry 可恢复")
    void outboxNonRetryableFailureBecomesDead() {
        OutboxRecord record =
                new OutboxRecord(
                        "m-1",
                        "probe.updated",
                        "p-1",
                        "eis://core/event/v1",
                        1,
                        new byte[] {1},
                        Map.of(),
                        Instant.now());
        transaction.executeWithoutResult(status -> outbox.append(record));
        ClaimedRecord claimed = outbox.claim(new ClaimRequest(10, Duration.ofSeconds(30))).get(0);

        outbox.markFailed(claimed.token(), new OutboxFailure("bad", "boom", false), Duration.ZERO);

        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT status, dead_at, last_error FROM outbox WHERE id = ?",
                        claimed.id());
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat(row.get("dead_at")).isNotNull();
        assertThat(row.get("last_error")).isEqualTo("boom");

        outbox.retry(claimed.id(), "ops");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM outbox WHERE id = ?",
                                String.class,
                                claimed.id()))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT dead_at FROM outbox WHERE id = ?",
                                java.sql.Timestamp.class,
                                claimed.id()))
                .isNull();
    }
}
