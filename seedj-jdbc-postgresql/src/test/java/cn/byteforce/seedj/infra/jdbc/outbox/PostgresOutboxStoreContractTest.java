package cn.byteforce.seedj.infra.jdbc.outbox;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import cn.byteforce.seedj.outbox.spi.OutboxStoreContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL outbox 跑公共契约套件（真实数据库，不使用 mock）。 */
@Testcontainers
class PostgresOutboxStoreContractTest extends OutboxStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static JdbcTemplate jdbcTemplate;
    private static OutboxStore store;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        new PostgresOutboxSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
        store = new PostgresOutboxStore(jdbcTemplate, () -> "outbox-" + seq.incrementAndGet());
    }

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Override
    protected void appendInTransaction(OutboxRecord record) {
        transaction.executeWithoutResult(status -> store.append(record));
    }

    @Override
    protected void reset() {
        jdbcTemplate.execute("TRUNCATE outbox, outbox_audit");
    }
}
