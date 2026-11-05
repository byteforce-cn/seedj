package cn.byteforce.seedj.jdbc;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.Migration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL 上的迁移执行器语义：版本、checksum、幂等、并发、回滚。
 */
@Testcontainers
class JdbcMigrationRunnerTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj_migration")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private JdbcMigrationRunner runner;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                DROP TABLE IF EXISTS seedj_schema_history, mig_test_a, mig_test_b, mig_other
                """);
        runner = new JdbcMigrationRunner(dataSource);
    }

    private static Migration create(String table) {
        return new Migration(1, "create " + table, "CREATE TABLE " + table + " (id INT);");
    }

    @Test
    @DisplayName("按版本执行迁移并记录 checksum")
    void migratesAndRecordsHistory() {
        runner.migrate(List.of(
                create("mig_test_a"),
                new Migration(2, "create mig_test_b",
                        "CREATE TABLE mig_test_b (id INT);")));

        assertThat(tableExists("mig_test_a")).isTrue();
        assertThat(tableExists("mig_test_b")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM seedj_schema_history", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT version, description FROM seedj_schema_history ORDER BY version"))
                .extracting(row -> row.get("version"), row -> row.get("description"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "create mig_test_a"),
                        org.assertj.core.groups.Tuple.tuple(2, "create mig_test_b"));
    }

    @Test
    @DisplayName("重复执行不会重复应用")
    void reapplyingIsNoOp() {
        List<Migration> migrations = List.of(create("mig_test_a"));

        runner.migrate(migrations);
        runner.migrate(migrations);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM seedj_schema_history", Integer.class)).isEqualTo(1);
        assertThat(runner.isUpToDate(migrations)).isTrue();
        runner.validate(migrations);
    }

    @Test
    @DisplayName("已应用迁移的 checksum 不一致时拒绝启动")
    void checksumMismatchFails() {
        runner.migrate(List.of(create("mig_test_a")));

        List<Migration> changed = List.of(new Migration(1, "create mig_test_a",
                "CREATE TABLE mig_test_a (id BIGINT);"));

        assertThatThrownBy(() -> runner.migrate(changed))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureErrorCode.MIGRATION_CHECKSUM_MISMATCH.name());
        assertThatThrownBy(() -> runner.validate(changed))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureErrorCode.MIGRATION_CHECKSUM_MISMATCH.name());
    }

    @Test
    @DisplayName("数据库中存在本地不认识的版本时拒绝启动")
    void unknownVersionFails() {
        runner.migrate(List.of(create("mig_test_a")));
        jdbcTemplate.update("""
                INSERT INTO seedj_schema_history
                    (scope, version, description, checksum, execution_ms)
                VALUES ('default', 99, 'future', repeat('0', 64), 1)
                """);

        assertThatThrownBy(() -> runner.migrate(List.of(create("mig_test_a"))))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureErrorCode.MIGRATION_UNKNOWN_VERSION.name());
    }

    @Test
    @DisplayName("不同 scope 的迁移互不干扰（同版本号可共存）")
    void scopesAreIsolated() {
        JdbcMigrationRunner other = new JdbcMigrationRunner(dataSource, "other");
        other.migrate(List.of(new Migration(1, "other table",
                "CREATE TABLE mig_other (id INT);")));

        runner.migrate(List.of(create("mig_test_a")));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM seedj_schema_history WHERE scope = 'default'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM seedj_schema_history WHERE scope = 'other'
                """, Integer.class)).isEqualTo(1);
        assertThat(tableExists("mig_test_a")).isTrue();
        assertThat(tableExists("mig_other")).isTrue();
    }

    @Test
    @DisplayName("两个实例并发启动时只执行一次（advisory lock）")
    void concurrentRunnersApplyOnce() throws Exception {
        List<Migration> migrations = List.of(create("mig_test_a"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(start);
                new JdbcMigrationRunner(dataSource).migrate(migrations);
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                new JdbcMigrationRunner(dataSource).migrate(migrations);
            });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM seedj_schema_history", Integer.class)).isEqualTo(1);
        assertThat(tableExists("mig_test_a")).isTrue();
    }

    @Test
    @DisplayName("存在未应用迁移时 isUpToDate=false，validate 抛 MIGRATION_PENDING")
    void pendingMigrationIsDetected() {
        List<Migration> migrations = List.of(create("mig_test_a"));

        assertThat(runner.isUpToDate(migrations)).isFalse();
        assertThatThrownBy(() -> runner.validate(migrations))
                .isInstanceOf(InfrastructureException.class)
                .extracting(e -> ((InfrastructureException) e).code())
                .isEqualTo(InfrastructureErrorCode.MIGRATION_PENDING.name());
    }

    @Test
    @DisplayName("迁移失败时整个事务回滚，不留下半成品表")
    void failureRollsBack() {
        Migration broken = new Migration(1, "broken", """
                CREATE TABLE mig_test_a (id INT);
                CREATE TABLE this_is_not_valid_sql ((;
                """);

        assertThatThrownBy(() -> runner.migrate(List.of(broken)))
                .isInstanceOf(InfrastructureException.class);

        assertThat(tableExists("mig_test_a")).isFalse();
        // 历史表与迁移在同一个事务里创建，失败时一并回滚
        assertThat(tableExists("seedj_schema_history")).isFalse();
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables WHERE table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
