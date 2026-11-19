package cn.byteforce.seedj.infra.jdbc.im;

import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImCardActionStoreContractTest;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL 实现跑通卡片动作契约。 */
@Testcontainers
class JdbcImCardActionStoreTest extends ImCardActionStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbc;

    private static JdbcImCardActionStore store;

    @BeforeAll
    static void initialize() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new ImSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        store = new JdbcImCardActionStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE im_card_action");
    }

    @Override
    protected ImCardActionStore store() {
        return store;
    }
}
