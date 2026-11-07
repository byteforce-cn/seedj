package cn.byteforce.seedj.conformance;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalDdl;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTable;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import cn.byteforce.seedj.server.hr.employee.HrEmployeeFields;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 版本 CAS：两个并发更正都带 expected=1，只能有一个赢家，另一个 409。
 */
@Testcontainers
class TemporalOptimisticConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static TemporalStore store;

    private static Clock clock;

    private static TransactionTemplate transactions;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcMigrationRunner runner = new JdbcMigrationRunner(dataSource);
        new TemporalSchemaCapability(runner).initialize();
        TemporalTable table = TemporalTable.from(HrEmployeeFields.schema());
        jdbc.execute(TemporalDdl.createTable(table));
        clock = () -> T0;
        store = new PostgresTemporalStore(jdbc, clock,
                new TemporalTables(List.of(table)));
    }

    @Test
    @DisplayName("两个并发更正同 expected version：一胜一 409，最终版本 2")
    void concurrentCorrectionsHaveOneWinner() throws Exception {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> store.append(AppendRequest.create("employee",
                id, T0, null, ACTOR, Map.of("employee_no", "EMP-900001", "name", "并发",
                        "status", "pending_onboarding",
                        "hire_date", java.time.LocalDate.of(2026, 10, 1)), 0L)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    transactions.executeWithoutResult(status -> store.append(
                            AppendRequest.correction("employee", id, T0, null, ACTOR,
                                    Map.of("employee_no", "EMP-900001", "name",
                                            Thread.currentThread().getName(),
                                            "status", "pending_onboarding",
                                            "hire_date", java.time.LocalDate.of(2026, 10, 1)),
                                    1L)));
                    return true;
                } catch (ContractException expected) {
                    assertThat(expected.code()).isEqualTo(ContractErrorCode.VERSION_CONFLICT);
                    conflicts.incrementAndGet();
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(task);
            Future<Boolean> second = pool.submit(task);
            start.countDown();

            long winners = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(winners).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(1);
            assertThat(store.current("employee", id).orElseThrow().version().version())
                    .isEqualTo(2);
            assertThat(store.history("employee", id, ScopePredicate.all())).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
