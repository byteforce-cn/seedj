package cn.byteforce.seedj.infra.jdbc.cqrs;

import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 → V2 升级证据：旧行（locator 非空、target_ref 为 null）仍可读，
 * 新行可以用 target_ref 代替 locator。
 */
@Testcontainers
class PostgresCommandStoreUpgradeTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final String FINGERPRINT = "a".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static PostgresCommandStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new CommandSchemaCapability(runner, List.of(CommandMigrations.v1())).initialize();
        new CommandSchemaCapability(runner, List.of(CommandMigrations.v1(),
                CommandMigrations.v2())).initialize();
        store = new PostgresCommandStore(jdbc);
    }

    @Test
    @DisplayName("V1 的 accepted 行在 V2 后仍可读，新行可写 target_ref")
    void upgradeKeepsOldRowsAndAcceptsTargetRef() {
        UUID legacyId = UUID.randomUUID();
        UUID locator = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO command_record
                    (id, type, issued_at, issuer, payload, idempotency_key,
                     payload_fingerprint, status, rejection_code, rejection_reason,
                     event_ids, locator, version, created_at, lease_until)
                VALUES (?, 'probe.command', now(), ?, '{}'::jsonb, 'k-v1', ?, 'accepted',
                        NULL, NULL, '[]'::jsonb, ?, 1, now(), now() + interval '60 seconds')
                """, legacyId, UUID.randomUUID(), FINGERPRINT, locator);

        CommandRecord legacy = store.find(legacyId).orElseThrow();
        assertThat(legacy.locator()).isEqualTo(locator);
        assertThat(legacy.targetRef()).isNull();
        assertThat(legacy.status()).isEqualTo(CommandStatus.ACCEPTED);

        CommandRecord pending = CommandRecord.pending(UUID.randomUUID(), "probe.command", NOW,
                UUID.randomUUID(), Map.of("name", "Alice"), "k-v2", FINGERPRINT, NOW);
        assertThat(store.insert(pending)).isTrue();
        store.complete(pending.accepted("emp-1", 1L, List.of()));

        CommandRecord advanced = store.find(pending.id()).orElseThrow();
        assertThat(advanced.locator()).isNull();
        assertThat(advanced.targetRef()).isEqualTo("emp-1");
        assertThat(advanced.version()).isEqualTo(1L);
    }
}
