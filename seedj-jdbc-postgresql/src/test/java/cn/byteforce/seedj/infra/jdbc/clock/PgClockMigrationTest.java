package cn.byteforce.seedj.infra.jdbc.clock;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.clock.HybridTimestamp;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PG 时钟适配器：迁移建表 + 状态存储 + 全局序列。 */
@Testcontainers
class PgClockMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static DriverManagerDataSource dataSource;

    private static JdbcMigrationRunner runner;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        runner = new JdbcMigrationRunner(dataSource);
        runner.migrate("clock", ClockMigrations.all());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("迁移创建 HLC 状态表与全局序列")
    void createsSchema() {
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.sys_hybrid_state')", String.class))
                .isEqualTo("sys_hybrid_state");
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.sys_global_seq')", String.class))
                .isEqualTo("sys_global_seq");
        assertThat(runner.isUpToDate("clock", ClockMigrations.all())).isTrue();
    }

    @Test
    @DisplayName("迁移可重复执行")
    void migrationIsIdempotent() {
        runner.migrate("clock", ClockMigrations.all());
        assertThat(runner.isUpToDate("clock", ClockMigrations.all())).isTrue();
    }

    @Test
    @DisplayName("状态存储往返且拒绝回退")
    void stateStoreRoundTripAndMonotonic() {
        PgHybridStateStore store = new PgHybridStateStore(dataSource);
        assertThat(store.load("node-a")).isNull();

        HybridTimestamp state = new HybridTimestamp(1_700_000_000_000_000L, 7L);
        store.save("node-a", state);
        assertThat(store.load("node-a")).isEqualTo(state);

        store.save("node-a", new HybridTimestamp(1L, 0L));
        assertThat(store.load("node-a")).isEqualTo(state);
    }

    @Test
    @DisplayName("全局序列递增")
    void globalSequencerIncrements() {
        PgGlobalSequencer sequencer = new PgGlobalSequencer(dataSource);
        long first = sequencer.next();
        assertThat(sequencer.next()).isEqualTo(first + 1);
    }
}
