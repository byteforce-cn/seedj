package cn.byteforce.seedj.infra.jdbc.ai;

import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import cn.byteforce.seedj.ai.spi.ExternalEffectStoreContractTest;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 实现跑通外部效果契约。 */
@Testcontainers
class PostgresExternalEffectStoreTest extends ExternalEffectStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static PostgresExternalEffectStore store;

    @BeforeAll
    static void initialize() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new AiSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        store = new PostgresExternalEffectStore(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE agent_external_effect");
    }

    @Override
    protected ExternalEffectStore store() {
        return store;
    }
}
