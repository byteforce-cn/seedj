package cn.byteforce.seedj.domain.jdbc;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.TemporalStoreContractTest;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL 实现跑公共契约套件（真实数据库 + 真实约束）。
 */
@Testcontainers
class PostgresTemporalStoreContractTest extends TemporalStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static TestClock clock;

    private static TemporalStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new TemporalSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        TemporalTable table = new TemporalTable("probe", "probe_object",
                new LinkedHashMap<>(Map.of("name", "TEXT")));
        jdbc.execute(TemporalDdl.createTable(table));
        clock = new TestClock(T0);
        store = new PostgresTemporalStore(jdbc, clock, new TemporalTables(List.of(table)));
    }

    @Override
    protected TemporalStore store() {
        return store;
    }

    @Override
    protected TestClock clock() {
        return clock;
    }

    @Override
    protected void reset() {
        jdbc.execute("TRUNCATE probe_object");
    }

    @Test
    void unknownObjectTypeIsRejectedClearly() {
        assertThatThrownBy(() -> store.find("missing", ID, TemporalQuery.current()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
