package cn.byteforce.seedj.cqrs.jdbc;

import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.CommandStoreContractTest;
import cn.byteforce.seedj.jdbc.JdbcMigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL 实现跑公共命令存储契约套件。
 */
@Testcontainers
class PostgresCommandStoreContractTest extends CommandStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static CommandStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new CommandSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        store = new PostgresCommandStore(jdbc);
    }

    @Override
    protected CommandStore store() {
        return store;
    }

    @Override
    protected void reset() {
        jdbc.execute("TRUNCATE command_record");
    }
}
