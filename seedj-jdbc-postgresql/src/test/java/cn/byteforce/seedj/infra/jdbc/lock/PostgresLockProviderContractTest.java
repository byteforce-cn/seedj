package cn.byteforce.seedj.infra.jdbc.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 租约锁跑公共契约 + 双 provider 竞争。 */
@Testcontainers
class PostgresLockProviderContractTest extends cn.byteforce.seedj.lock.LockProviderContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new PostgresLockSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
    }

    @BeforeEach
    void clearLeases() {
        jdbc.execute("TRUNCATE seedj_lock_lease");
    }

    @Override
    protected LockProvider newProvider() {
        return new PostgresLockProvider(jdbc);
    }

    @Test
    @DisplayName("两个 provider 同时抢同一键：只有一个成功")
    void twoProvidersRaceForSameKey() throws Exception {
        LockProvider first = new PostgresLockProvider(jdbc);
        LockProvider second = new PostgresLockProvider(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<LockLease>> a =
                    pool.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return first.tryAcquire("order:race", Duration.ofSeconds(30));
                            });
            Future<Optional<LockLease>> b =
                    pool.submit(
                            () -> {
                                start.await(5, TimeUnit.SECONDS);
                                return second.tryAcquire("order:race", Duration.ofSeconds(30));
                            });
            start.countDown();

            long winners =
                    (a.get(30, TimeUnit.SECONDS).isPresent() ? 1 : 0)
                            + (b.get(30, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
